package io.github.masterplaycoding.documentkit.android

import android.content.Context
import android.net.Uri
import io.github.masterplaycoding.documentkit.AssetId
import io.github.masterplaycoding.documentkit.DocumentCodec
import io.github.masterplaycoding.documentkit.DocumentError
import io.github.masterplaycoding.documentkit.DocumentException
import io.github.masterplaycoding.documentkit.io.AssetSource
import io.github.masterplaycoding.documentkit.io.DocumentStore
import io.github.masterplaycoding.documentkit.io.OpenedDocument
import io.github.masterplaycoding.documentkit.io.SaveReceipt
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Imports and exports DocumentKit containers through Android's Storage Access
 * Framework.
 *
 * A provider URI is not a file path. There is no guarantee it is seekable, it
 * may not be backed by a local file at all, and the provider - not the app -
 * owns what happens at the destination. That difference is the entire reason
 * this class exists rather than the local save path being reused.
 *
 * This class does **not** request permissions or launch a picker. Those are UI
 * decisions with lifecycle implications, and they belong to the application.
 * Pass it a URI you already obtained.
 */
public class DocumentTransfer(
    private val context: Context,
    private val store: DocumentStore = DocumentStore(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** This library's own staging area inside the app's private cache. */
    private val stagingDirectory: File
        get() = File(context.cacheDir, STAGING_DIRECTORY_NAME).apply { mkdirs() }

    /**
     * Copies a document from [uri] into private storage, then opens it.
     *
     * The copy happens first because verification needs to read the archive's
     * central directory, and a provider stream is not reliably seekable. The
     * copy is bounded by the store's archive-size limit, so a provider serving
     * an endless stream fills a bounded file and then fails, rather than
     * filling the device.
     *
     * The returned handle **owns** the staging copy and deletes it on `close`.
     * Use it with `use { }`.
     */
    public suspend fun <T : Any> import(uri: Uri, codec: DocumentCodec<T>): OpenedDocument<T> =
        withContext(dispatcher) {
            val staging = File.createTempFile("import-", ".dkit", stagingDirectory)

            try {
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw DocumentException(
                        // A null stream is a real outcome here - a revoked
                        // permission, a deleted document, a provider that no
                        // longer resolves - not an impossible one.
                        DocumentError.IoFailure(
                            "import",
                            "the provider returned no input stream for $uri",
                        ),
                    )

                val limit = store.limits.maxArchiveBytes
                var copied = 0L
                input.use { source ->
                    staging.outputStream().buffered().use { target ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) {
                            val read = source.read(buffer)
                            if (read < 0) break

                            copied += read
                            if (copied > limit) {
                                throw DocumentException(
                                    DocumentError.LimitExceeded("archive size", limit),
                                )
                            }
                            target.write(buffer, 0, read)
                        }
                    }
                }

                // openStaged hands ownership of the file to the returned
                // handle, and deletes it itself if opening fails.
                store.openStaged(staging, codec)
            } catch (cause: Throwable) {
                staging.delete()
                throw cause
            }
        }

    /**
     * Writes a document to [uri] as a complete copy.
     *
     * The archive is built and fully verified in private storage *before* the
     * destination stream is opened. Opening the provider's stream is the point
     * of no return: on most providers it truncates the target immediately, so
     * anything that could fail must fail before that happens.
     *
     * The copy itself is not atomic, and this does not pretend otherwise. The
     * provider owns the destination and offers no replacement primitive, so an
     * interruption partway through leaves the destination partially written -
     * which is why [SaveReceipt.ProviderManagedExport] is a different receipt
     * from [SaveReceipt.AtomicReplace], and why the sample offers "Save a
     * copy" rather than advertising a crash-safe overwrite.
     */
    public suspend fun <T : Any> exportCopy(
        uri: Uri,
        document: T,
        documentId: String,
        codec: DocumentCodec<T>,
        assets: Map<AssetId, AssetSource> = emptyMap(),
    ): SaveReceipt.ProviderManagedExport = withContext(dispatcher) {
        val archive = store.buildVerifiedArchive(
            workspace = stagingDirectory,
            document = document,
            documentId = documentId,
            codec = codec,
            assets = assets,
        )

        try {
            val output = context.contentResolver.openOutputStream(uri, "wt")
                ?: throw DocumentException(
                    DocumentError.IoFailure(
                        "export",
                        "the provider returned no output stream for $uri",
                    ),
                )

            val written = try {
                output.use { target ->
                    archive.inputStream().use { source -> source.copyTo(target, BUFFER_BYTES) }
                }
            } catch (cause: IOException) {
                // Past the point of no return. Say so plainly: the user's
                // chosen destination may now hold a partial file, and telling
                // them it simply "failed" would be misleading.
                throw DocumentException(
                    DocumentError.IoFailure(
                        "export",
                        "writing to $uri failed after the destination was opened, so it may " +
                            "contain partial content: ${cause.message}",
                    ),
                )
            }

            SaveReceipt.ProviderManagedExport(uri.toString(), written)
        } finally {
            archive.delete()
        }
    }

    /**
     * Deletes files left in this library's own staging directory.
     *
     * Ordinary failures and cancellation clean up after themselves. Hard
     * process termination cannot, so an application may call this on a cold
     * start.
     *
     * It touches only DocumentKit's own subdirectory of the app cache. A
     * library that scanned the whole cache for files it guessed were stale
     * would eventually delete something belonging to somebody else.
     */
    public fun cleanStagingDirectory(): Int {
        val staging = File(context.cacheDir, STAGING_DIRECTORY_NAME)
        if (!staging.isDirectory) return 0

        return staging.listFiles().orEmpty().count { it.isFile && it.delete() }
    }

    private companion object {
        const val STAGING_DIRECTORY_NAME = "documentkit-staging"
        const val BUFFER_BYTES = 64 * 1024
    }
}
