package io.github.masterplaycoding.documentkit.cli

import io.github.masterplaycoding.documentkit.AssetEntry
import io.github.masterplaycoding.documentkit.AssetId
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

    // --- the hostile corpus, through the command line -----------------------

    /**
     * Every shape documentkit-io's corpus rejects, as a file on disk.
     *
     * The library's own tests prove each is refused. They say nothing about
     * what happens when the refusal has to travel out through a command: a
     * DocumentException escaping `run` would reach a user as a Kotlin stack
     * trace and an exit code nobody chose.
     */
    private fun hostileContainers(): Map<String, File> {
        val cases = linkedMapOf<String, File>()

        cases["not an archive"] = File(workspace, "text.dkit").apply { writeText("plain text") }
        cases["an empty file"] = File(workspace, "empty.dkit").apply { writeBytes(ByteArray(0)) }

        cases["a truncated archive"] = File(workspace, "truncated.dkit").also { target ->
            val whole = wellFormed("source.dkit").readBytes()
            target.writeBytes(whole.copyOf(whole.size / 2))
        }

        cases["a missing manifest"] = TestArchive(File(workspace, "no-manifest.dkit"))
            .entry(DocumentKitFormat.DOCUMENT_ENTRY, """{"title":"x","notes":[]}""")
            .build()

        cases["a missing document"] = TestArchive(File(workspace, "no-document.dkit"))
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

        cases["a path traversal entry"] =
            TestArchive.wellFormed(File(workspace, "traversal.dkit"))
                .entry("../escape.txt", "payload")
                .build()

        cases["an entry outside the format"] =
            TestArchive.wellFormed(File(workspace, "stowaway.dkit"))
                .entry("notes.txt", "extra")
                .build()

        cases["a manifest that is not JSON"] = TestArchive(File(workspace, "bad-json.dkit"))
            .entry(DocumentKitFormat.MANIFEST_ENTRY, """{"container_version":}""")
            .entry(DocumentKitFormat.DOCUMENT_ENTRY, "{}")
            .build()

        cases["a manifest that is JSON but not an object"] =
            TestArchive(File(workspace, "array.dkit"))
                .entry(DocumentKitFormat.MANIFEST_ENTRY, "[]")
                .entry(DocumentKitFormat.DOCUMENT_ENTRY, "{}")
                .build()

        cases["a container from a newer writer"] =
            TestArchive.wellFormed(File(workspace, "future.dkit"), containerVersion = 99).build()

        return cases
    }

    @Test
    fun validateCallsEveryHostileContainerInvalid() {
        for ((description, target) in hostileContainers()) {
            out.clear()
            err.clear()

            // If run() throws, this test fails with that exception, which is
            // the assertion: a user gets a verdict, not a stack trace.
            val code = exec("validate", target.path)

            assertEquals(EXIT_INVALID, code, "validate on $description returned $code")
        }
    }

    @Test
    fun inspectSurvivesEveryHostileContainer() {
        // Weaker than validate on purpose. inspect reports what a container
        // says about itself and verifies nothing, so a file whose manifest
        // parses can legitimately be inspected even when it is unusable - a
        // missing document.json is validate's business, not inspect's. What
        // inspect owes the user is a verdict rather than a crash, and never
        // exit 2, which means "you typed something wrong".
        for ((description, target) in hostileContainers()) {
            out.clear()
            err.clear()

            val code = exec("inspect", target.path)

            assertTrue(
                code == EXIT_OK || code == EXIT_INVALID,
                "inspect on $description returned $code, which is an invocation error",
            )
        }
    }

    @Test
    fun everyHostileContainerProducesAReasonSomeoneCanActOn() {
        for ((description, target) in hostileContainers()) {
            out.clear()
            err.clear()
            exec("validate", target.path)

            val said = (stdout() + stderr()).trim()
            assertTrue(said.isNotEmpty(), "validate said nothing at all about $description")
            // A bare exception *type* is allowed and deliberate: DecodeFailure
            // keeps it when no field name can be extracted, because "something
            // was structurally wrong here" beats no detail at all. What must
            // never escape is a stack trace or a package path, which is the
            // shape that says nobody handled this.
            assertFalse(
                said.contains("\tat ") || said.contains("kotlinx.serialization."),
                "validate leaked an unhandled failure for $description: $said",
            )
        }
    }

    @Test
    fun inspectRefusesAManifestWhoseLengthsCannotAddUp() {
        // inspect verifies nothing, which makes it tempting to assume it will
        // report whatever the manifest claims. It will not: the manifest is
        // validated before a summary is built, so lengths that cannot add up
        // are a refusal rather than a number nobody can use.
        //
        // This is what keeps declaredContentBytes out of reach of an
        // overflowing manifest on this path. The saturating sum behind it is
        // still the property that must hold, and DocumentSummaryTest exercises
        // it directly, because DocumentSummary is public and a caller can
        // build one without coming through here.
        val target = TestArchive(File(workspace, "overflow.dkit"))
            .entry(
                DocumentKitFormat.MANIFEST_ENTRY,
                """{"container_version":1,"application_id":"example.notebook",""" +
                    """"schema_version":2,"document_id":"x",""" +
                    """"document_length":${Long.MAX_VALUE},""" +
                    """"document_sha256":"${"a".repeat(64)}",""" +
                    """"assets":[{"id":"big","length":${Long.MAX_VALUE},""" +
                    """"sha256":"${"b".repeat(64)}"}]}""",
            )
            .entry(DocumentKitFormat.DOCUMENT_ENTRY, "{}")
            .entry("assets/big", "x")
            .build()

        assertEquals(EXIT_INVALID, exec("inspect", target.path, "--json"))
        assertFalse(
            stdout().contains("declaredContentBytes"),
            "a manifest that does not add up must not produce a summary at all",
        )
    }

    @Test
    fun theJsonOutputStaysWellFormedForABrokenContainer() {
        // The failure mode this catches: a broken container producing half a
        // JSON document, or a diagnostic printed before it. A caller piping
        // this into a parser gets one or the other, and only ever one.
        for ((description, target) in hostileContainers()) {
            out.clear()
            err.clear()
            val code = exec("validate", target.path, "--json")

            assertEquals(EXIT_INVALID, code, "on $description")
            val text = stdout().trim()
            assertTrue(
                text.startsWith("{") && text.endsWith("}"),
                "validate --json emitted something that is not one JSON object for " +
                    "$description: $text",
            )
        }
    }

    // --- output a hostile file cannot take over ---------------------------

    /**
     * Characters a terminal acts on or a reader cannot see, planted in every
     * string a manifest or an archive leaves unconstrained.
     *
     * Built from code points so no escape sequence has to survive an editor,
     * and listed here rather than taken from the production predicate: a
     * check that asked the code under test what counts as dangerous would
     * pass whenever that code was wrong in the same way.
     */
    private val planted = listOf(
        Char(0x1b) to "ESC",
        Char(0x9b) to "C1 CSI",
        Char(0x07) to "BEL",
        Char(0x7f) to "DEL",
        Char(0x0d) to "carriage return",
        Char(0x202e) to "right-to-left override",
        Char(0x2028) to "line separator",
    )

    private fun hostileText(prefix: String): String =
        prefix + planted.joinToString("") { (character, _) -> "${character}x" }

    private fun containerWithHostileStrings(name: String, strayEntry: Boolean): File {
        val body = """{"title":"Fixture","notes":[]}""".toByteArray()
        val cover = ByteArray(16) { 3 }
        val archive = TestArchive(File(workspace, name)).manifest(
            Manifest(
                containerVersion = DocumentKitFormat.CONTAINER_VERSION,
                applicationId = hostileText("example.notebook"),
                schemaVersion = 2,
                documentId = hostileText("fixture"),
                documentLength = body.size.toLong(),
                documentSha256 = TestArchive.sha256(body),
                assets = listOf(
                    AssetEntry(
                        id = AssetId.of("cover"),
                        length = cover.size.toLong(),
                        sha256 = TestArchive.sha256(cover),
                        mediaType = hostileText("image/png"),
                    ),
                ),
            ),
        )
        archive.entry(DocumentKitFormat.DOCUMENT_ENTRY, body)
        archive.entry(DocumentKitFormat.ASSET_PREFIX + "cover", cover)
        if (strayEntry) archive.entry(hostileText("stowaway") + ".txt", "extra")
        return archive.build()
    }

    private fun assertNothingPlantedIn(said: String, context: String) {
        for ((character, label) in planted) {
            assertFalse(
                said.contains(character),
                "$context printed a raw $label from the file:\n$said",
            )
        }
    }

    @Test
    fun noStringFromTheFileReachesTheTerminalRaw() {
        val cases = mapOf(
            "hostile ids and media type" to containerWithHostileStrings("ids.dkit", strayEntry = false),
            "a hostile entry name" to containerWithHostileStrings("entry.dkit", strayEntry = true),
        )
        for ((description, target) in cases) {
            for (command in listOf("inspect", "validate")) {
                for (json in listOf(false, true)) {
                    out.clear()
                    err.clear()
                    val args = listOfNotNull(command, target.path, "--json".takeIf { json })
                    val code = exec(*args.toTypedArray())

                    val context = "${args.first()}${if (json) " --json" else ""} on $description"
                    assertTrue(code == EXIT_OK || code == EXIT_INVALID, "$context returned $code")
                    // Lines are joined with \n, which is fine; the planted
                    // carriage return is not.
                    assertNothingPlantedIn(stdout() + stderr(), context)
                    // Escaped, not dropped: that the id contains an ESC is
                    // the most useful thing the reader can learn about it.
                    assertContains((stdout() + stderr()).lowercase(), "\\u001b", message = context)
                }
            }
        }

        // Escaping must not change the verdict on a container that is fine.
        out.clear()
        err.clear()
        assertEquals(EXIT_OK, exec("validate", cases.getValue("hostile ids and media type").path))
    }

    @Test
    fun aHostilePathIsEscapedWhenItIsNotAFile() {
        val missing = File(workspace, hostileText("missing") + ".dkit")

        assertEquals(EXIT_USAGE, exec("validate", missing.path))
        assertNothingPlantedIn(stdout() + stderr(), "the not-a-file message")
    }

    private fun readEntry(archive: File, name: String): ByteArray =
        java.util.zip.ZipFile(archive).use { zip ->
            zip.getInputStream(zip.getEntry(name)).use { it.readBytes() }
        }
}
