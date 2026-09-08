package io.github.masterplaycoding.documentkit.io

import io.github.masterplaycoding.documentkit.AssetEntry
import io.github.masterplaycoding.documentkit.AssetId
import io.github.masterplaycoding.documentkit.DocumentError
import io.github.masterplaycoding.documentkit.DocumentException
import io.github.masterplaycoding.documentkit.DocumentKitFormat
import io.github.masterplaycoding.documentkit.DocumentLimits
import io.github.masterplaycoding.documentkit.Manifest
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Every container here is hostile or damaged. The library must reject each one
 * with a specific, named error - never with a crash, a hang, an unbounded
 * allocation, or a silently degraded document.
 */
class MalformedInputTest {

    private lateinit var workspace: File
    private val store = DocumentStore()

    @BeforeTest
    fun setUp() {
        workspace = File.createTempFile("documentkit-malformed-", "").apply {
            delete()
            mkdirs()
        }
    }

    @AfterTest
    fun tearDown() {
        workspace.deleteRecursively()
    }

    private fun file(name: String = "case.dkit") = File(workspace, name)

    private suspend inline fun <reified E : DocumentError> assertRejects(file: File): E {
        val failure = assertFailsWith<DocumentException> { store.open(file, notebookCodec) }
        return assertIs<E>(failure.error)
    }

    // --- structure ---------------------------------------------------------

    @Test
    fun rejectsSomethingThatIsNotAnArchiveAtAll() = runTest {
        val target = file().apply { writeText("this is a text file, not a zip") }

        assertRejects<DocumentError.IoFailure>(target)
    }

    @Test
    fun rejectsTruncatedArchiveData() = runTest {
        val target = TestArchive.wellFormed(file()).build()
        val bytes = target.readBytes()
        target.writeBytes(bytes.copyOf(bytes.size / 2))

        // Whatever the ZIP layer makes of half a file, it must not become an
        // open document.
        assertFailsWith<DocumentException> { store.open(target, notebookCodec) }
    }

    @Test
    fun rejectsAMissingManifest() = runTest {
        val target = TestArchive(file())
            .entry(DocumentKitFormat.DOCUMENT_ENTRY, """{"title":"x","notes":[]}""")
            .build()

        assertEquals(DocumentKitFormat.MANIFEST_ENTRY, assertRejects<DocumentError.MissingEntry>(target).entry)
    }

    @Test
    fun rejectsAMissingDocument() = runTest {
        val target = TestArchive(file())
            .manifest(
                Manifest(
                    containerVersion = 1,
                    applicationId = "example.notebook",
                    schemaVersion = 2,
                    documentId = "x",
                    documentLength = 0,
                    documentSha256 = TestArchive.sha256(ByteArray(0)),
                ),
            )
            .build()

        assertEquals(DocumentKitFormat.DOCUMENT_ENTRY, assertRejects<DocumentError.MissingEntry>(target).entry)
    }

    @Test
    fun rejectsADuplicateEntryName() = runTest {
        // A ZIP may declare the same name twice, and which copy a reader picks
        // is a fine way to disagree with the application that wrote the file.
        // java.util.zip's *writer* refuses to produce this, which is why the
        // fixture is emitted at byte level - nothing stops an attacker, or a
        // writer in another language, from creating one.
        val honest = """{"title":"Fixture","notes":[]}"""
        val target = RawZip()
            .entry(
                DocumentKitFormat.MANIFEST_ENTRY,
                manifestFor(honest),
            )
            .entry(DocumentKitFormat.DOCUMENT_ENTRY, honest)
            .entry(DocumentKitFormat.DOCUMENT_ENTRY, """{"title":"Impostor","notes":[]}""")
            .writeTo(file())

        assertEquals(
            DocumentKitFormat.DOCUMENT_ENTRY,
            assertRejects<DocumentError.DuplicateEntry>(target).entry,
        )
    }

    private fun manifestFor(body: String): String {
        val bytes = body.toByteArray()
        return """
            {"container_version":1,"application_id":"example.notebook","schema_version":2,
             "document_id":"fixture-1","document_length":${bytes.size},
             "document_sha256":"${TestArchive.sha256(bytes)}","assets":[]}
        """.trimIndent().replace("\n", "")
    }

    @Test
    fun rejectsPathTraversalAndAbsolutePaths() = runTest {
        val hostile = listOf(
            "../escape.txt",
            "assets/../../escape.txt",
            "/etc/passwd",
            "assets\\windows.png",
            "C:/Windows/System32/x",
            "assets//empty-segment",
        )

        for ((index, name) in hostile.withIndex()) {
            val target = TestArchive.wellFormed(file("case-$index.dkit"))
                .entry(name, "payload")
                .build()

            val error = assertRejects<DocumentError.InvalidEntry>(target)
            assertEquals(name, error.entry, "should have rejected '$name'")
        }
    }

    @Test
    fun rejectsAnEntryOutsideTheFormat() = runTest {
        val target = TestArchive.wellFormed(file()).entry("notes.txt", "extra").build()

        // Not ignored. An unknown entry is content this build does not
        // understand, and carrying it silently across a save loses it.
        assertEquals("notes.txt", assertRejects<DocumentError.InvalidEntry>(target).entry)
    }

    @Test
    fun rejectsDirectoryEntries() = runTest {
        val target = TestArchive.wellFormed(file()).directory("assets").build()

        assertRejects<DocumentError.InvalidEntry>(target)
    }

    @Test
    fun rejectsAnAssetWhoseNameIsNotAValidAssetId() = runTest {
        val target = TestArchive.wellFormed(file())
            .entry("assets/.hidden", "payload")
            .build()

        assertRejects<DocumentError.InvalidEntry>(target)
    }

    // --- limits ------------------------------------------------------------

    @Test
    fun stopsAtTheEntryCountLimit() = runTest {
        // The limit is lowered rather than the fixture grown: the same
        // behaviour, without a huge file in the repository.
        val small = DocumentStore(DocumentLimits(maxEntryCount = 4))
        val archive = TestArchive.wellFormed(file())
        repeat(10) { index -> archive.entry("assets/pad$index", "x") }

        val failure = assertFailsWith<DocumentException> { small.open(archive.build(), notebookCodec) }
        assertEquals("entry count", assertIs<DocumentError.LimitExceeded>(failure.error).limit)
    }

    @Test
    fun stopsAtTheTotalContentLimitEvenWhenTheManifestUnderstatesIt() = runTest {
        // The manifest is honest here; the point is that the *counted* bytes
        // are what the limit is applied to.
        val payload = ByteArray(8 * 1024) { 1 }
        val target = TestArchive.wellFormed(
            file(),
            body = """{"title":"Big","notes":[]}""",
            assets = mapOf("big" to payload),
        ).build()

        val small = DocumentStore(DocumentLimits(maxTotalUncompressedBytes = 1024))
        val failure = assertFailsWith<DocumentException> { small.open(target, notebookCodec) }
        assertIs<DocumentError.LimitExceeded>(failure.error)
    }

    @Test
    fun rejectsAnArchiveLargerThanTheFileSizeLimit() = runTest {
        val target = TestArchive.wellFormed(file()).build()
        val small = DocumentStore(DocumentLimits(maxArchiveBytes = 16))

        val failure = assertFailsWith<DocumentException> { small.open(target, notebookCodec) }
        assertEquals("archive size", assertIs<DocumentError.LimitExceeded>(failure.error).limit)
    }

    @Test
    fun rejectsDeeplyNestedJsonBeforeParsingIt() = runTest {
        val nested = "[".repeat(300) + "]".repeat(300)
        val target = TestArchive(file())
            .entry(DocumentKitFormat.MANIFEST_ENTRY, nested)
            .entry(DocumentKitFormat.DOCUMENT_ENTRY, "{}")
            .build()

        val error = assertRejects<DocumentError.InvalidJson>(target)
        assertTrue(error.reason.contains("deep"))
    }

    // --- integrity ---------------------------------------------------------

    @Test
    fun rejectsADocumentWhoseDigestDoesNotMatch() = runTest {
        val documentBytes = """{"title":"Original","notes":[]}""".toByteArray()
        val target = TestArchive(file())
            .manifest(
                Manifest(
                    containerVersion = 1,
                    applicationId = "example.notebook",
                    schemaVersion = 2,
                    documentId = "fixture-1",
                    documentLength = documentBytes.size.toLong(),
                    documentSha256 = TestArchive.sha256(documentBytes),
                ),
            )
            // Same length, different bytes: a length check alone would pass.
            .entry(DocumentKitFormat.DOCUMENT_ENTRY, """{"title":"Tampered","notes":[]}""")
            .build()

        val error = assertRejects<DocumentError.IntegrityMismatch>(target)
        assertEquals(DocumentKitFormat.DOCUMENT_ENTRY, error.entry)
        assertTrue(error.expected.startsWith("sha256"))
    }

    @Test
    fun rejectsAnAssetWhoseDeclaredLengthIsWrong() = runTest {
        val payload = "hello".toByteArray()
        val target = TestArchive(file())
            .manifest(
                Manifest(
                    containerVersion = 1,
                    applicationId = "example.notebook",
                    schemaVersion = 2,
                    documentId = "fixture-1",
                    documentLength = 26,
                    documentSha256 = TestArchive.sha256("""{"title":"Fixture","notes":[]}""".toByteArray()),
                    assets = listOf(
                        AssetEntry(AssetId.of("a"), length = 9_999, sha256 = TestArchive.sha256(payload)),
                    ),
                ),
            )
            .entry(DocumentKitFormat.DOCUMENT_ENTRY, """{"title":"Fixture","notes":[]}""")
            .entry("assets/a", payload)
            .build()

        assertRejects<DocumentError.IntegrityMismatch>(target)
    }

    @Test
    fun rejectsAnIndexedAssetThatIsNotPresent() = runTest {
        val payload = "hello".toByteArray()
        val body = """{"title":"Fixture","notes":[]}"""
        val target = TestArchive(file())
            .manifest(
                Manifest(
                    containerVersion = 1,
                    applicationId = "example.notebook",
                    schemaVersion = 2,
                    documentId = "fixture-1",
                    documentLength = body.toByteArray().size.toLong(),
                    documentSha256 = TestArchive.sha256(body.toByteArray()),
                    assets = listOf(
                        AssetEntry(AssetId.of("ghost"), payload.size.toLong(), TestArchive.sha256(payload)),
                    ),
                ),
            )
            .entry(DocumentKitFormat.DOCUMENT_ENTRY, body)
            .build()

        assertRejects<DocumentError.MissingEntry>(target)
    }

    @Test
    fun rejectsAnAssetPresentButNotIndexed() = runTest {
        // Content that is never verified is content that could be anything.
        val target = TestArchive.wellFormed(file())
            .entry("assets/unlisted", "payload")
            .build()

        assertRejects<DocumentError.InvalidManifest>(target)
    }

    @Test
    fun rejectsAManifestThatIsNotValidUtf8() = runTest {
        val target = TestArchive(file())
            .entry(DocumentKitFormat.MANIFEST_ENTRY, byteArrayOf(0x7B, 0xC3.toByte(), 0x28, 0x7D))
            .entry(DocumentKitFormat.DOCUMENT_ENTRY, "{}")
            .build()

        val error = assertRejects<DocumentError.InvalidJson>(target)
        assertTrue(error.reason.contains("UTF-8"))
    }

    @Test
    fun rejectsAMalformedManifest() = runTest {
        val target = TestArchive(file())
            .entry(DocumentKitFormat.MANIFEST_ENTRY, """{"container_version":}""")
            .entry(DocumentKitFormat.DOCUMENT_ENTRY, "{}")
            .build()

        assertRejects<DocumentError.InvalidJson>(target)
    }

    // --- versions ----------------------------------------------------------

    @Test
    fun rejectsANewerContainerVersion() = runTest {
        val target = TestArchive.wellFormed(file(), containerVersion = 99).build()

        val error = assertRejects<DocumentError.UnsupportedContainer>(target)
        assertEquals(99, error.found)
        assertEquals(DocumentKitFormat.CONTAINER_VERSION, error.supported)
    }

    @Test
    fun rejectsANewerApplicationSchema() = runTest {
        val target = TestArchive.wellFormed(file(), schemaVersion = 7).build()

        val error = assertRejects<DocumentError.UnsupportedSchema>(target)
        assertEquals(7, error.found)
        assertEquals(2, error.supported)
    }

    @Test
    fun rejectsAnUnknownFieldRatherThanDroppingIt() = runTest {
        val body = """{"title":"Fixture","notes":[],"future_field":42}"""
        val target = TestArchive.wellFormed(file(), body = body).build()

        // Dropping this quietly would lose the field on the next save.
        assertRejects<DocumentError.InvalidJson>(target)
    }
}
