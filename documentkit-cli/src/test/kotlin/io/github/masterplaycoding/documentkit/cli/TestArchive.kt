package io.github.masterplaycoding.documentkit.cli

import io.github.masterplaycoding.documentkit.AssetEntry
import io.github.masterplaycoding.documentkit.AssetId
import io.github.masterplaycoding.documentkit.DocumentKitFormat
import io.github.masterplaycoding.documentkit.Manifest
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json

/**
 * Builds deliberately malformed containers.
 *
 * The fixtures are generated rather than committed, and the tests that need to
 * cross a limit lower the limit instead of growing the file. A repository full
 * of 256 MB archives would be a poor trade for the same coverage.
 */
class TestArchive(private val file: File) {

    private val entries = mutableListOf<Pair<String, ByteArray>>()

    fun entry(name: String, bytes: ByteArray): TestArchive = apply { entries += name to bytes }

    fun entry(name: String, text: String): TestArchive = entry(name, text.toByteArray())

    fun directory(name: String): TestArchive = apply { entries += "$name/" to ByteArray(0) }

    fun manifest(manifest: Manifest): TestArchive =
        entry(DocumentKitFormat.MANIFEST_ENTRY, json.encodeToString(Manifest.serializer(), manifest))

    fun build(): File {
        ZipOutputStream(file.outputStream().buffered()).use { output ->
            for ((name, bytes) in entries) {
                output.putNextEntry(ZipEntry(name))
                output.write(bytes)
                output.closeEntry()
            }
        }
        return file
    }

    companion object {
        private val json = Json { prettyPrint = false }

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
                "%02x".format(byte.toInt() and 0xFF)
            }

        /** A well-formed notebook container, as a starting point to corrupt. */
        fun wellFormed(
            file: File,
            body: String = """{"title":"Fixture","notes":[]}""",
            schemaVersion: Int = 2,
            applicationId: String = "example.notebook",
            containerVersion: Int = DocumentKitFormat.CONTAINER_VERSION,
            assets: Map<String, ByteArray> = emptyMap(),
        ): TestArchive {
            val documentBytes = body.toByteArray()
            val archive = TestArchive(file)

            archive.manifest(
                Manifest(
                    containerVersion = containerVersion,
                    applicationId = applicationId,
                    schemaVersion = schemaVersion,
                    documentId = "fixture-1",
                    documentLength = documentBytes.size.toLong(),
                    documentSha256 = sha256(documentBytes),
                    assets = assets.map { (id, bytes) ->
                        AssetEntry(
                            id = AssetId.of(id),
                            length = bytes.size.toLong(),
                            sha256 = sha256(bytes),
                        )
                    },
                ),
            )
            archive.entry(DocumentKitFormat.DOCUMENT_ENTRY, documentBytes)
            for ((id, bytes) in assets) {
                archive.entry(DocumentKitFormat.ASSET_PREFIX + id, bytes)
            }
            return archive
        }
    }
}
