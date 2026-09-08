package io.github.masterplaycoding.documentkit.cli

import io.github.masterplaycoding.documentkit.DocumentKitFormat
import io.github.masterplaycoding.documentkit.Manifest
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The CLI's contract is its exit codes and its output, so that is what these
 * exercise — through `run`, which returns the code rather than calling
 * exitProcess.
 *
 * The malformed fixtures are the same shapes documentkit-io's corpus uses.
 * Reusing them keeps one description of what a broken container looks like.
 */
class CliTest {

    private lateinit var workspace: File
    private val out = mutableListOf<String>()
    private val err = mutableListOf<String>()

    @BeforeTest
    fun setUp() {
        workspace = File.createTempFile("documentkit-cli-", "").apply {
            delete()
            mkdirs()
        }
        out.clear()
        err.clear()
    }

    @AfterTest
    fun tearDown() {
        workspace.deleteRecursively()
    }

    private fun exec(vararg args: String): Int = run(arrayOf(*args), out::add, err::add)

    private fun stdout() = out.joinToString("\n")
    private fun stderr() = err.joinToString("\n")

    private fun wellFormed(name: String = "doc.dkit"): File =
        TestArchive.wellFormed(
            File(workspace, name),
            assets = mapOf("cover" to ByteArray(2048) { 7 }),
        ).build()

    // --- exit codes ---------------------------------------------------------

    @Test
    fun aValidDocumentExitsZero() {
        assertEquals(EXIT_OK, exec("validate", wellFormed().path))
        assertContains(stdout(), "valid")
    }

    @Test
    fun anInvalidDocumentExitsOne() {
        // The manifest describes one body; the archive holds another of the
        // same length. Only the digest catches it - which is the case worth
        // pinning, since a length check alone would pass this file.
        val declared = """{"title":"Original","notes":[]}""".toByteArray()
        val target = TestArchive(File(workspace, "damaged.dkit"))
            .manifest(
                Manifest(
                    containerVersion = 1,
                    applicationId = "example.notebook",
                    schemaVersion = 2,
                    documentId = "fixture-1",
                    documentLength = declared.size.toLong(),
                    documentSha256 = TestArchive.sha256(declared),
                ),
            )
            .entry(DocumentKitFormat.DOCUMENT_ENTRY, """{"title":"Tampered","notes":[]}""")
            .build()

        assertEquals(EXIT_INVALID, exec("validate", target.path))
        assertContains(stdout(), "IntegrityMismatch")
    }

    @Test
    fun aBadInvocationExitsTwo() {
        // Deliberately distinct from exit 1. A build that cannot tell "your
        // document is corrupt" from "you typed the wrong flag" teaches people
        // to ignore both.
        assertEquals(EXIT_USAGE, exec("inspect", File(workspace, "absent.dkit").path))
        assertEquals(EXIT_USAGE, exec("frobnicate", wellFormed().path))
        assertEquals(EXIT_USAGE, exec("validate", "--nonsense", wellFormed().path))
        assertEquals(EXIT_USAGE, exec("validate"))
    }

    @Test
    fun helpExitsZero() {
        assertEquals(EXIT_OK, exec("--help"))
        assertContains(stdout(), "documentkit inspect")
    }

    // --- inspect ------------------------------------------------------------

    @Test
    fun inspectReportsWhatTheContainerSaysAboutItself() {
        assertEquals(EXIT_OK, exec("inspect", wellFormed().path))

        val text = stdout()
        assertContains(text, "example.notebook")
        assertContains(text, "container version   1")
        assertContains(text, "cover")
        // inspect verifies nothing, and must not let a reader think it did.
        assertContains(text, "declared, not verified")
    }

    @Test
    fun inspectOnAFileThatIsNotAnArchiveIsADocumentProblem() {
        val target = File(workspace, "notes.txt").apply { writeText("not a zip") }

        // Exit 1, not 2: the file exists and was readable. The verdict is
        // about the document.
        assertEquals(EXIT_INVALID, exec("inspect", target.path))
    }

    @Test
    fun inspectEmitsJson() {
        assertEquals(EXIT_OK, exec("inspect", wellFormed().path, "--json"))

        val json = stdout()
        assertContains(json, """"applicationId":"example.notebook"""")
        assertContains(json, """"verified":false""")
        assertContains(json, """"id":"cover"""")
    }

    // --- validate -----------------------------------------------------------

    @Test
    fun validateNamesEveryProblemRatherThanOnlyTheFirst() {
        val body = """{"title":"Fixture","notes":[]}"""
        val target = TestArchive.wellFormed(File(workspace, "messy.dkit"), body = body)
            .entry("notes.txt", "unexpected")
            .entry("../escape.txt", "hostile")
            .build()

        assertEquals(EXIT_INVALID, exec("validate", target.path))

        val text = stdout()
        assertContains(text, "InvalidEntry")
        // A thoroughly broken file should not take one run per error.
        assertTrue(
            text.lines().count { it.contains("✗") } >= 2,
            "expected several problems to be listed:\n$text",
        )
    }

    @Test
    fun validateReportsAnAssetWhoseDigestDoesNotMatch() {
        val payload = "hello".toByteArray()
        val body = """{"title":"Fixture","notes":[]}"""
        val target = TestArchive.wellFormed(
            File(workspace, "asset.dkit"),
            body = body,
            assets = mapOf("cover" to payload),
        ).build()

        // Rewrite the asset with equal-length, different bytes.
        val rebuilt = TestArchive(File(workspace, "tampered.dkit"))
        rebuilt.entry(DocumentKitFormat.MANIFEST_ENTRY, readEntry(target, DocumentKitFormat.MANIFEST_ENTRY))
        rebuilt.entry(DocumentKitFormat.DOCUMENT_ENTRY, body)
        rebuilt.entry("assets/cover", "HELLO".toByteArray())

        assertEquals(EXIT_INVALID, exec("validate", rebuilt.build().path))

        val text = stdout()
        assertContains(text, "IntegrityMismatch")
        assertContains(text, "assets/cover")
        // The document body still verified, and saying so localises the damage.
        assertContains(text, "✓ ${DocumentKitFormat.DOCUMENT_ENTRY}")
    }

    @Test
    fun validateEmitsJsonWithItsScope() {
        assertEquals(EXIT_OK, exec("validate", wellFormed().path, "--json"))

        val json = stdout()
        assertContains(json, """"ok":true""")
        assertContains(json, """"verifiedEntries":["document.json","assets/cover"]""")
        // A green result must not imply more than it checked.
        assertContains(json, "application schema not checked")
    }

    @Test
    fun validateSaysWhatItDidNotCheck() {
        assertEquals(EXIT_OK, exec("validate", wellFormed().path))
        assertContains(stdout(), "application schema not checked")
    }

    @Test
    fun validateHonoursALoweredLimit() {
        val target = wellFormed()

        assertEquals(EXIT_INVALID, exec("validate", target.path, "--max-archive-bytes", "16"))
        assertContains(stdout(), "LimitExceeded")
    }

    @Test
    fun aRejectedLimitIsAnInvocationError() {
        assertEquals(EXIT_USAGE, exec("validate", wellFormed().path, "--max-archive-bytes", "zero"))
        assertFalse(stderr().isEmpty())
    }

    private fun readEntry(archive: File, name: String): ByteArray =
        java.util.zip.ZipFile(archive).use { zip ->
            zip.getInputStream(zip.getEntry(name)).use { it.readBytes() }
        }
}
