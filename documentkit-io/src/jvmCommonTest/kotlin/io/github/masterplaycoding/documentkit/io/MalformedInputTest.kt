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

    // --- archives that are structurally legal but not containers -----------

    @Test
    fun rejectsAValidArchiveWithNoEntriesAtAll() = runTest {
        // A perfectly well-formed ZIP holding nothing. The reader has no
        // manifest to consult and no entry to name, which is exactly the shape
        // that tends to produce a null dereference rather than an error.
        val target = RawZip().writeTo(file())

        assertEquals(DocumentKitFormat.MANIFEST_ENTRY, assertRejects<DocumentError.MissingEntry>(target).entry)
    }

    @Test
    fun rejectsAnEntryWhoseNameIsEmpty() = runTest {
        val honest = """{"title":"Fixture","notes":[]}"""
        val target = RawZip()
            .entry(DocumentKitFormat.MANIFEST_ENTRY, manifestFor(honest))
            .entry(DocumentKitFormat.DOCUMENT_ENTRY, honest)
            .entry("", "payload")
            .writeTo(file())

        assertRejects<DocumentError.InvalidEntry>(target)
    }

    @Test
    fun matchesFormatEntryNamesCaseSensitively() = runTest {
        // MANIFEST.JSON is not manifest.json. A case-insensitive match would
        // let a second, differently-cased copy sit beside the real one, and
        // which of the two a reader honours would depend on its filesystem
        // rather than on the file.
        val honest = """{"title":"Fixture","notes":[]}"""
        val target = RawZip()
            .entry(DocumentKitFormat.MANIFEST_ENTRY, manifestFor(honest))
            .entry(DocumentKitFormat.DOCUMENT_ENTRY, honest)
            .entry("Document.json", """{"title":"Impostor","notes":[]}""")
            .writeTo(file())

        // Rejected as an unknown entry, not silently accepted as a duplicate
        // of the real document.
        assertEquals("Document.json", assertRejects<DocumentError.InvalidEntry>(target).entry)
    }

    @Test
    fun rejectsAnAssetPrefixInTheWrongCase() = runTest {
        val target = TestArchive.wellFormed(file()).entry("Assets/cover", "payload").build()

        assertEquals("Assets/cover", assertRejects<DocumentError.InvalidEntry>(target).entry)
    }

    /**
     * A DocumentKit container that is also some other file - a ZIP polyglot -
     * still reads as the container it is.
     *
     * Both halves are pinned because the answer surprised me, and an
     * unexamined "surprising but fine" is how a real hole gets waved through.
     * Trailing bytes leave every offset correct. Leading bytes shift all of
     * them, and the end-of-central-directory record is located by scanning
     * backwards from the end, so a reader has to rebase - which java.util.zip
     * does, in common with essentially every other ZIP implementation.
     *
     * That is acceptable here, and worth saying why rather than only that.
     * The content read is the genuine content in both cases; DocumentKit never
     * executes anything, and whether a file is executable is decided by its
     * extension and permissions, not by what a library made of its bytes. What
     * would matter is two readers disagreeing about the *content* of one file,
     * and rebasing is what prevents that rather than causes it.
     *
     * The limit this leaves: DocumentKit does not certify that a container is
     * *only* a container. An application that treats "opened successfully" as
     * "this file is inert" is relying on something the library never promised.
     */
    @Test
    fun readsAContainerWithBytesAppendedAfterIt() = runTest {
        val target = TestArchive.wellFormed(file()).build()
        val original = target.readBytes()
        target.writeBytes(original + "TRAILING GARBAGE".toByteArray())

        store.open(target, notebookCodec).use { opened ->
            assertEquals("Fixture", opened.document.title)
        }
    }

    @Test
    fun readsAContainerWithBytesPrependedBeforeIt() = runTest {
        val target = TestArchive.wellFormed(file()).build()
        val original = target.readBytes()
        target.writeBytes("#!/bin/sh\necho hello\n".toByteArray() + original)

        // The real document, not a shifted misread of it.
        store.open(target, notebookCodec).use { opened ->
            assertEquals("Fixture", opened.document.title)
            assertEquals(emptySet(), opened.assetIds)
        }
    }

    // --- manifests that parse but are not manifests -------------------------

    @Test
    fun rejectsAManifestThatIsValidJsonButNotAnObject() = runTest {
        for ((index, body) in listOf("[]", "\"manifest\"", "42", "true", "null").withIndex()) {
            val target = TestArchive(file("shape-$index.dkit"))
                .entry(DocumentKitFormat.MANIFEST_ENTRY, body)
                .entry(DocumentKitFormat.DOCUMENT_ENTRY, "{}")
                .build()

            assertRejects<DocumentError.InvalidJson>(target)
        }
    }

    @Test
    fun rejectsAManifestCarryingAByteOrderMark() = runTest {
        // A BOM is what a Windows text editor adds when someone repairs a
        // container by hand. It must produce a named JSON error rather than a
        // parser exception nobody can act on.
        val honest = """{"title":"Fixture","notes":[]}"""
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val target = TestArchive(file())
            .entry(DocumentKitFormat.MANIFEST_ENTRY, bom + manifestFor(honest).toByteArray())
            .entry(DocumentKitFormat.DOCUMENT_ENTRY, honest)
            .build()

        assertRejects<DocumentError.InvalidJson>(target)
    }

    @Test
    fun rejectsAManifestMissingARequiredField() = runTest {
        // Every field but document_sha256. kotlinx reports a missing required
        // field, and the reader has to turn that into a document error rather
        // than let a serialization exception escape.
        val target = TestArchive(file())
            .entry(
                DocumentKitFormat.MANIFEST_ENTRY,
                """{"container_version":1,"application_id":"example.notebook","schema_version":2,""" +
                    """"document_id":"fixture-1","document_length":2,"assets":[]}""",
            )
            .entry(DocumentKitFormat.DOCUMENT_ENTRY, "{}")
            .build()

        assertRejects<DocumentError.InvalidJson>(target)
    }
}
