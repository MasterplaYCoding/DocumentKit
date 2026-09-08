package io.github.masterplaycoding.documentkit.io

import io.github.masterplaycoding.documentkit.AssetEntry
import io.github.masterplaycoding.documentkit.AssetId
import io.github.masterplaycoding.documentkit.DecodeResult
import io.github.masterplaycoding.documentkit.DocumentCodec
import io.github.masterplaycoding.documentkit.DocumentError
import io.github.masterplaycoding.documentkit.DocumentException
import io.github.masterplaycoding.documentkit.DocumentKitFormat
import io.github.masterplaycoding.documentkit.DocumentLimits
import io.github.masterplaycoding.documentkit.JsonDepth
import io.github.masterplaycoding.documentkit.Manifest
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.coroutines.currentCoroutineContext

/**
 * Reads and writes DocumentKit containers on a local filesystem.
 *
 * Every operation that touches a disk suspends and runs on the I/O dispatcher.
 * Cancellation propagates as ordinary coroutine cancellation, and temporary
 * files are cleaned up on every path out, including cancellation.
 *
 * A store instance is safe to share. An [OpenedDocument] is not.
 */
public class DocumentStore(
    public val limits: DocumentLimits = DocumentLimits.Default,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val manifestJson = Json { ignoreUnknownKeys = false }

    /** Destinations currently being written or read through this store. */
    private val openDestinations = mutableSetOf<String>()

    /**
     * Opens and fully verifies a document.
     *
     * The pipeline, in order:
     *
     * 1. bound the archive file's own size,
     * 2. validate the entry inventory (names, duplicates, unknown entries),
     * 3. read and validate the manifest,
     * 4. stream **all** declared content, checking actual lengths and digests,
     * 5. decode the document body, migrate it, decode the model, validate it,
     * 6. check every referenced asset exists.
     *
     * Verification is complete before this returns. An application that gets a
     * handle back has a document whose bytes have been checked, so later asset
     * access can be lazy without being unsafe. The cost is that opening reads
     * the whole archive once; the benefit is that a corrupt asset surfaces when
     * the user opens the file, not an hour later when they scroll to it.
     */
    public suspend fun <T : Any> open(
        file: File,
        codec: DocumentCodec<T>,
    ): OpenedDocument<T> = withContext(dispatcher) {
        openInternal(file, codec, ownedStagingFile = null)
    }

    /**
     * Opens a document from a staging file this store then owns.
     *
     * Used by the Android module after copying a provider URI into private
     * storage: the resulting handle deletes the staging file when closed.
     */
    public suspend fun <T : Any> openStaged(
        stagingFile: File,
        codec: DocumentCodec<T>,
    ): OpenedDocument<T> = withContext(dispatcher) {
        try {
            openInternal(stagingFile, codec, ownedStagingFile = stagingFile)
        } catch (cause: Throwable) {
            // The handle would have owned this file; since there is no handle,
            // nothing else will ever delete it.
            stagingFile.delete()
            throw cause
        }
    }

    private suspend fun <T : Any> openInternal(
        file: File,
        codec: DocumentCodec<T>,
        ownedStagingFile: File?,
    ): OpenedDocument<T> {
        if (!file.isFile) {
            throw DocumentException(
                DocumentError.IoFailure("open", "'${file.path}' is not a file"),
            )
        }
        if (file.length() > limits.maxArchiveBytes) {
            throw DocumentException(
                DocumentError.LimitExceeded("archive size", limits.maxArchiveBytes),
            )
        }

        val archive = try {
            ZipFile(file)
        } catch (cause: Exception) {
            throw DocumentException(
                DocumentError.IoFailure("open", cause.message ?: "not a readable archive"),
            )
        }

        var handOver = false
        try {
            val inventory = ArchiveInventory.validate(archive, limits)
            inventory.errors.firstOrNull()?.let { throw DocumentException(it) }

            val manifest = readManifest(archive)
            manifest.validate().firstOrNull()?.let { throw DocumentException(it) }

            if (manifest.applicationId != codec.applicationId) {
                throw DocumentException(
                    DocumentError.WrongApplication(manifest.applicationId, codec.applicationId),
                )
            }
            if (manifest.schemaVersion > codec.schemaVersion) {
                throw DocumentException(
                    DocumentError.UnsupportedSchema(manifest.schemaVersion, codec.schemaVersion),
                )
            }

            val body = verifyContent(archive, manifest, inventory.assets)

            val parsed = codec.parseBody(body).getOrElse { cause ->
                throw DocumentException(
                    DocumentError.InvalidJson(
                        DocumentKitFormat.DOCUMENT_ENTRY,
                        cause.message ?: "malformed JSON",
                    ),
                )
            }

            val decoded = when (val result = codec.decode(parsed, manifest.schemaVersion)) {
                is DecodeResult.Failure -> throw DocumentException(result.error)
                is DecodeResult.Success -> result
            }

            val present = manifest.assets.map { it.id }.toSet()
            for (referenced in codec.referencedAssets(decoded.document)) {
                if (referenced !in present) {
                    throw DocumentException(
                        DocumentError.MissingReferencedAsset(referenced.value),
                    )
                }
            }

            handOver = true
            return OpenedDocument(
                document = decoded.document,
                manifest = manifest,
                migrationsApplied = decoded.migrationsApplied,
                archive = archive,
                ownedStagingFile = ownedStagingFile,
            )
        } finally {
            if (!handOver) archive.close()
        }
    }

    private fun readManifest(archive: ZipFile): Manifest {
        val entry = archive.getEntry(DocumentKitFormat.MANIFEST_ENTRY)
            ?: throw DocumentException(
                DocumentError.MissingEntry(DocumentKitFormat.MANIFEST_ENTRY),
            )

        val bytes = archive.getInputStream(entry)
            .use { it.readAtMost(limits.maxManifestBytes, "manifest size") }

        val text = decodeUtf8(bytes, DocumentKitFormat.MANIFEST_ENTRY)
        JsonDepth.exceeds(text, limits.maxJsonDepth)?.let { depth ->
            throw DocumentException(
                DocumentError.InvalidJson(
                    DocumentKitFormat.MANIFEST_ENTRY,
                    "nested $depth levels deep, limit is ${limits.maxJsonDepth}",
                ),
            )
        }

        return try {
            manifestJson.decodeFromString(Manifest.serializer(), text)
        } catch (cause: Exception) {
            throw DocumentException(
                DocumentError.InvalidJson(
                    DocumentKitFormat.MANIFEST_ENTRY,
                    cause.message ?: "could not decode the manifest",
                ),
            )
        }
    }

    /**
     * Streams every declared entry, checking real lengths and digests, and
     * returns the document body's bytes.
     *
     * The manifest's declared totals are not trusted for anything: the running
     * total counted here is what the limit applies to. A manifest claiming a
     * 1 KB asset that expands to 4 GB is exactly the case this defends
     * against, and it is the case a declared-size check would miss.
     */
    private suspend fun verifyContent(
        archive: ZipFile,
        manifest: Manifest,
        assetEntries: Map<AssetId, ZipEntry>,
    ): ByteArray {
        var totalBytes = 0L

        val documentEntry = archive.getEntry(DocumentKitFormat.DOCUMENT_ENTRY)
            ?: throw DocumentException(
                DocumentError.MissingEntry(DocumentKitFormat.DOCUMENT_ENTRY),
            )
        val documentBytes = archive.getInputStream(documentEntry)
            .use { it.readAtMost(limits.maxDocumentBytes, "document size") }

        totalBytes += documentBytes.size
        checkIntegrity(
            entry = DocumentKitFormat.DOCUMENT_ENTRY,
            expectedLength = manifest.documentLength,
            expectedSha256 = manifest.documentSha256,
            actualLength = documentBytes.size.toLong(),
            actualSha256 = documentBytes.sha256(),
        )

        // The manifest index and the physical entries must agree in both
        // directions. An indexed asset with no entry is a broken document; an
        // entry with no index line is content this build would carry across a
        // save without ever having verified it.
        val indexed = manifest.assets.associateBy { it.id }
        for (id in assetEntries.keys) {
            if (id !in indexed) {
                throw DocumentException(
                    DocumentError.InvalidManifest("asset '${id.value}' is present but not indexed"),
                )
            }
        }

        for (asset in manifest.assets) {
            currentCoroutineContext().ensureActive()

            val entry = assetEntries[asset.id]
                ?: throw DocumentException(
                    DocumentError.MissingEntry(DocumentKitFormat.assetPath(asset.id)),
                )

            val remaining = limits.maxTotalUncompressedBytes - totalBytes
            val cap = minOf(limits.maxAssetBytes, maxOf(remaining, 0L))
            val measured = archive.getInputStream(entry)
                .use { it.copyMeasured(target = null, limit = cap, limitName = "total content size") }

            totalBytes += measured.length
            checkIntegrity(
                entry = DocumentKitFormat.assetPath(asset.id),
                expectedLength = asset.length,
                expectedSha256 = asset.sha256,
                actualLength = measured.length,
                actualSha256 = measured.sha256,
            )
        }

        return documentBytes
    }

    private fun checkIntegrity(
        entry: String,
        expectedLength: Long,
        expectedSha256: String,
        actualLength: Long,
        actualSha256: String,
    ) {
        if (actualLength != expectedLength) {
            throw DocumentException(
                DocumentError.IntegrityMismatch(
                    entry,
                    "$expectedLength bytes",
                    "$actualLength bytes",
                ),
            )
        }
        if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
            throw DocumentException(
                DocumentError.IntegrityMismatch(entry, "sha256 $expectedSha256", "sha256 $actualSha256"),
            )
        }
    }

    private fun decodeUtf8(bytes: ByteArray, entry: String): String {
        val text = bytes.toString(Charsets.UTF_8)
        // Charsets.UTF_8 substitutes U+FFFD for invalid sequences rather than
        // failing, so malformed input would otherwise decode to something
        // plausible-looking. Round-tripping catches that.
        if (!text.toByteArray(Charsets.UTF_8).contentEquals(bytes)) {
            throw DocumentException(DocumentError.InvalidJson(entry, "not valid UTF-8"))
        }
        return text
    }

    /**
     * Saves a document, replacing [destination] atomically.
     *
     * The sequence, and why it is in this order:
     *
     * 1. Build the complete archive in a sibling temporary file. A sibling,
     *    not the system temp directory, because the final step has to be a
     *    move within one filesystem to be atomic.
     * 2. Digest and measure while streaming, so the manifest describes what
     *    was actually written rather than what was expected.
     * 3. Close it, then re-open and fully verify it - the same verification an
     *    ordinary open performs. A file that would not load is not allowed to
     *    become the user's document.
     * 4. Force the bytes to storage.
     * 5. Move it over the destination atomically.
     *
     * The old document is untouched until step 5. Every failure before then
     * leaves the user with exactly what they had.
     *
     * If the filesystem cannot do an atomic move, this fails with
     * [DocumentError.AtomicReplaceUnsupported] rather than falling back to a
     * copy. A caller who asked for atomic replacement and silently got a
     * truncated file instead would have no way to find out.
     */
    public suspend fun <T : Any> save(
        destination: File,
        document: T,
        documentId: String,
        codec: DocumentCodec<T>,
        assets: Map<AssetId, AssetSource> = emptyMap(),
    ): SaveReceipt = withContext(dispatcher) {
        require(documentId.isNotBlank()) { "documentId must not be blank" }

        val key = destination.absolutePath
        synchronized(openDestinations) {
            check(openDestinations.add(key)) {
                "'$key' is already being written through this store; close old handles and " +
                    "serialise writes to the same document"
            }
        }

        try {
            val staged = buildArchive(
                workspace = destination.absoluteFile.parentFile ?: File("."),
                document = document,
                documentId = documentId,
                codec = codec,
                assets = assets,
            )

            try {
                // Verify what we are about to make the user's document.
                openInternal(staged, codec, ownedStagingFile = null).close()

                val bytes = staged.length()
                LocalFiles.atomicReplace(staged, destination)
                SaveReceipt.AtomicReplace(destination.absolutePath, bytes)
            } catch (cause: Throwable) {
                staged.delete()
                throw cause
            }
        } finally {
            synchronized(openDestinations) { openDestinations.remove(key) }
        }
    }

    /**
     * Builds a complete archive in [workspace] and verifies it, returning the
     * file. **The caller owns the result and must delete it.**
     *
     * This is the operation an exporter needs: somewhere that is not a local
     * filesystem path - an Android content provider, say - still requires a
     * finished, verified archive before anything is written to a destination
     * it cannot roll back.
     *
     * Prefer [save] for local files. That runs this and then replaces the
     * destination atomically, which this does not do.
     */
    public suspend fun <T : Any> buildVerifiedArchive(
        workspace: File,
        document: T,
        documentId: String,
        codec: DocumentCodec<T>,
        assets: Map<AssetId, AssetSource> = emptyMap(),
    ): File = withContext(dispatcher) {
        val staged = buildArchive(workspace, document, documentId, codec, assets)
        try {
            openInternal(staged, codec, ownedStagingFile = null).close()
            staged
        } catch (cause: Throwable) {
            staged.delete()
            throw cause
        }
    }

    /**
     * Builds a complete, self-consistent archive in a temporary file and
     * returns it. Not verified: [buildVerifiedArchive] and [save] do that.
     *
     * Assets are streamed twice - once to measure and digest, once to write -
     * rather than buffered in memory. Two passes over a 200 MB video is far
     * cheaper than one 200 MB allocation, and it means the manifest's digests
     * describe bytes that were genuinely read from the source.
     */
    private suspend fun <T : Any> buildArchive(
        workspace: File,
        document: T,
        documentId: String,
        codec: DocumentCodec<T>,
        assets: Map<AssetId, AssetSource>,
    ): File {
        workspace.mkdirs()
        val staged = File.createTempFile("documentkit-", ".tmp", workspace)

        try {
            val documentBytes = codec.encode(document)
            if (documentBytes.size > limits.maxDocumentBytes) {
                throw DocumentException(
                    DocumentError.LimitExceeded("document size", limits.maxDocumentBytes),
                )
            }

            // Measure assets first, so the manifest can be written as the very
            // first entry and a reader never has to seek to the end to find it.
            var totalBytes = documentBytes.size.toLong()
            val entries = mutableListOf<AssetEntry>()
            for ((id, source) in assets) {
                currentCoroutineContext().ensureActive()

                val remaining = limits.maxTotalUncompressedBytes - totalBytes
                val cap = minOf(limits.maxAssetBytes, maxOf(remaining, 0L))
                val measured = source.openStream()
                    .use { it.copyMeasured(target = null, limit = cap, limitName = "total content size") }

                totalBytes += measured.length
                entries += AssetEntry(id = id, length = measured.length, sha256 = measured.sha256)
            }

            val manifest = Manifest(
                containerVersion = DocumentKitFormat.CONTAINER_VERSION,
                applicationId = codec.applicationId,
                schemaVersion = codec.schemaVersion,
                documentId = documentId,
                documentLength = documentBytes.size.toLong(),
                documentSha256 = documentBytes.sha256(),
                assets = entries,
            )

            ZipOutputStream(staged.outputStream().buffered()).use { output ->
                writeEntry(
                    output,
                    DocumentKitFormat.MANIFEST_ENTRY,
                    manifestJson.encodeToString(Manifest.serializer(), manifest).toByteArray(),
                )
                writeEntry(output, DocumentKitFormat.DOCUMENT_ENTRY, documentBytes)

                for (entry in entries) {
                    currentCoroutineContext().ensureActive()
                    val source = assets.getValue(entry.id)

                    output.putNextEntry(ZipEntry(DocumentKitFormat.assetPath(entry.id)))
                    val written = source.openStream()
                        .use { it.copyMeasured(output, limits.maxAssetBytes, "asset size") }
                    output.closeEntry()

                    // The source changed under us between the two passes. The
                    // manifest we already built no longer describes the bytes,
                    // so the archive is wrong - fail rather than ship it.
                    if (written.sha256 != entry.sha256) {
                        throw DocumentException(
                            DocumentError.IoFailure(
                                "save",
                                "asset '${entry.id}' changed while it was being written",
                            ),
                        )
                    }
                }
            }

            LocalFiles.forceToStorage(staged)
            return staged
        } catch (cause: Throwable) {
            staged.delete()
            throw cause
        }
    }

    private fun writeEntry(output: ZipOutputStream, path: String, bytes: ByteArray) {
        output.putNextEntry(ZipEntry(path))
        output.write(bytes)
        output.closeEntry()
    }

    /**
     * Opens a staged archive purely to confirm it is readable, then closes it.
     *
     * Used by exporters that build an archive before handing it to a
     * destination they cannot roll back.
     */
    internal suspend fun <T : Any> verifyArchive(file: File, codec: DocumentCodec<T>) {
        openInternal(file, codec, ownedStagingFile = null).close()
    }

    /** Writes an already-built archive to an arbitrary stream, for exporters. */
    internal fun copyArchiveTo(archive: File, target: OutputStream): Long =
        archive.inputStream().use { input -> input.copyTo(target, COPY_BUFFER_BYTES) }
}
