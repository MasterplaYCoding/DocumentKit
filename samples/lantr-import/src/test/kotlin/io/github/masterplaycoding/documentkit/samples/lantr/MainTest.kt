package io.github.masterplaycoding.documentkit.samples.lantr

import io.github.masterplaycoding.documentkit.io.DocumentStore
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive

/** The command's contract: exit codes, and output a hostile file cannot take over. */
class MainTest {

    private lateinit var workspace: File
    private val out = mutableListOf<String>()
    private val err = mutableListOf<String>()

    @BeforeTest
    fun setUp() {
        workspace = File.createTempFile("lantr-main-", "").apply {
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

    private fun said(): String = (out + err).joinToString("\n")

    /**
     * Built from code points, and listed here rather than taken from the code
     * under test, so a wrong escaper cannot also be the judge of itself.
     */
    private val planted = listOf(
        Char(0x1b) to "ESC",
        Char(0x9b) to "C1 CSI",
        Char(0x07) to "BEL",
        Char(0x0d) to "carriage return",
        Char(0x202e) to "right-to-left override",
    )

    private fun hostile(prefix: String): String = prefix + planted.joinToString("") { (character, _) -> "${character}x" }

    @Test
    fun importsAndNamesWhatItLeftBehind() {
        val source = LtrnFixture.complete(File(workspace, "talk.ltrn")).build()

        assertEquals(0, exec(source.path, File(workspace, "talk.dkit").path))
        assertContains(said(), "imported 2 sections, 3 slides, 1 images into talk.dkit")
        assertContains(said(), "not carried across")
    }

    @Test
    fun nothingFromTheFileReachesTheTerminalRaw() {
        val source = LtrnFixture(File(workspace, "hostile.ltrn"))
            .entry("metadata.json", """{"title":"Hostile"}""")
            .entry("arc/blocks.json", """{"block_ids":["b"]}""")
            .entry("arc/blocks/block_001.json", """{"id":"b","name":"S","slide_ids":["s"]}""")
            .entry(
                "slides/slide_001.json",
                """{"id":"s","blocks":[""" +
                    """{"block_type":"image","image_path":${JsonPrimitive(hostile("picture"))}},""" +
                    """{"block_type":${JsonPrimitive(hostile("video"))}}]}""",
            )
            .entry(hostile("images/odd") + ".png", ByteArray(8))
            .build()

        assertEquals(0, exec(source.path, File(workspace, "hostile.dkit").path))

        for ((character, label) in planted) {
            assertFalse(said().contains(character), "printed a raw $label from the file:\n${said()}")
        }
        // Escaped rather than dropped: that a name holds an ESC is the finding.
        assertContains(said(), "\\u001b")
    }

    @Test
    fun aFileThatIsNotALantrArchiveExitsOne() {
        val source = File(workspace, "notes.ltrn").apply { writeText("plain text") }

        assertEquals(1, exec(source.path, File(workspace, "notes.dkit").path))
        assertContains(said(), "not a ZIP archive")
    }

    @Test
    fun aSourceWithNoNameStillImports() = runBlocking {
        // nameWithoutExtension of ".ltrn" is empty, and a container's id must
        // not be blank.
        val source = LtrnFixture.complete(File(workspace, ".ltrn")).build()
        val destination = File(workspace, "unnamed.dkit")

        assertEquals(0, exec(source.path, destination.path), said())
        assertEquals("imported", DocumentStore().inspect(destination).documentId)
    }

    @Test
    fun badInvocationsExitTwo() {
        val source = LtrnFixture.complete(File(workspace, "talk.ltrn")).build()

        assertEquals(2, exec(source.path))
        assertEquals(2, exec(File(workspace, "absent.ltrn").path, File(workspace, "out.dkit").path))
        assertEquals(2, exec(source.path, source.path))
    }
}
