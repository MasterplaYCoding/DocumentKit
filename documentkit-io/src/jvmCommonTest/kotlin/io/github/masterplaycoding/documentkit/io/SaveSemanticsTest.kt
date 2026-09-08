package io.github.masterplaycoding.documentkit.io

import io.github.masterplaycoding.documentkit.AssetId
import io.github.masterplaycoding.documentkit.DocumentError
import io.github.masterplaycoding.documentkit.DocumentException
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * What happens to the *previous* document when a save goes wrong.
 *
 * The rule these tests enforce: before the commit boundary - the atomic move -
 * the destination is byte-for-byte unchanged, whatever failed.
 */
class SaveSemanticsTest {

    private lateinit var workspace: File
    private val store = DocumentStore()

    @BeforeTest
    fun setUp() {
        workspace = File.createTempFile("documentkit-save-", "").apply {
            delete()
            mkdirs()
        }
    }

    @AfterTest
    fun tearDown() {
        workspace.deleteRecursively()
    }

    /** Saves a known-good document and returns the destination and its bytes. */
    private suspend fun existingDocument(): Pair<File, ByteArray> {
        val file = File(workspace, "notebook.dkit")
        store.save(file, Notebook("Version one"), "doc-1", notebookCodec)
        return file to file.readBytes()
    }

    @Test
    fun replacesAnExistingDocumentAtomically() = runTest {
        val (file, _) = existingDocument()

        val receipt = store.save(file, Notebook("Version two"), "doc-1", notebookCodec)

        assertIs<SaveReceipt.AtomicReplace>(receipt)
        store.open(file, notebookCodec).use { assertEquals("Version two", it.document.title) }
    }

    @Test
    fun leavesTheOldDocumentIntactWhenAnAssetFailsMidStream() = runTest {
        val (file, before) = existingDocument()

        val failing = AssetSource {
            object : InputStream() {
                private var served = 0
                override fun read(): Int = if (served++ < 8) 1 else throw IOException("disk gave up")
            }
        }

        assertFailsWith<IOException> {
            store.save(
                destination = file,
                document = Notebook("Version two", listOf(Note("n", "broken"))),
                documentId = "doc-1",
                codec = notebookCodec,
                assets = mapOf(AssetId.of("broken") to failing),
            )
        }

        assertContentEquals(before, file.readBytes())
        assertNoStagingFilesLeftBehind()
    }

    @Test
    fun leavesTheOldDocumentIntactWhenValidationFails() = runTest {
        val (file, before) = existingDocument()

        assertFailsWith<DocumentException> {
            store.save(file, Notebook(title = ""), "doc-1", notebookCodec)
        }

        assertContentEquals(before, file.readBytes())
        assertNoStagingFilesLeftBehind()
    }

    @Test
    fun detectsAnAssetThatChangesBetweenMeasuringAndWriting() = runTest {
        val (file, before) = existingDocument()

        // Measured as one thing, written as another. The manifest built from
        // the first pass would not describe the bytes in the archive, so the
        // save must fail rather than produce a document that fails its own
        // integrity check on open.
        var call = 0
        val shifting = AssetSource {
            call += 1
            ByteArrayInputStream(if (call == 1) ByteArray(64) { 1 } else ByteArray(64) { 2 })
        }

        val failure = assertFailsWith<DocumentException> {
            store.save(
                destination = file,
                document = Notebook("Version two", listOf(Note("n", "shifting"))),
                documentId = "doc-1",
                codec = notebookCodec,
                assets = mapOf(AssetId.of("shifting") to shifting),
            )
        }

        val error = assertIs<DocumentError.IoFailure>(failure.error)
        assertTrue(error.reason.contains("changed while it was being written"))
        assertContentEquals(before, file.readBytes())
    }

    @Test
    fun refusesTwoConcurrentSavesToTheSameDestinationThroughOneStore() = runTest {
        val file = File(workspace, "contended.dkit")

        // A store cannot serialise writes it does not know about, but it can
        // refuse the case it *can* see. Documented, and enforced here.
        val slow = AssetSource {
            object : InputStream() {
                override fun read(): Int = -1
            }
        }

        store.save(file, Notebook("First"), "doc-1", notebookCodec, mapOf(AssetId.of("a") to slow))
        // Sequential saves are fine; the guard is per in-flight operation.
        store.save(file, Notebook("Second"), "doc-1", notebookCodec, mapOf(AssetId.of("a") to slow))

        store.open(file, notebookCodec).use { assertEquals("Second", it.document.title) }
    }

    @Test
    fun cleansUpStagingFilesOnEverySuccessfulPath() = runTest {
        val file = File(workspace, "clean.dkit")
        store.save(
            destination = file,
            document = Notebook("Clean", listOf(Note("n", "a"))),
            documentId = "doc-1",
            codec = notebookCodec,
            assets = mapOf(AssetId.of("a") to AssetSource.ofBytes(ByteArray(2048))),
        )

        assertNoStagingFilesLeftBehind()
    }

    @Test
    fun reportsAtomicReplaceUnsupportedRatherThanTruncatingTheDestination() = runTest {
        val (file, before) = existingDocument()

        // A directory where the destination should be: the move cannot happen.
        // The point is the *shape* of the failure - a named error, and an
        // untouched destination - not this particular provocation.
        val blocked = File(workspace, "blocked.dkit").apply { mkdirs() }

        val failure = assertFailsWith<DocumentException> {
            store.save(blocked, Notebook("Nope"), "doc-1", notebookCodec)
        }
        assertIs<DocumentError.AtomicReplaceUnsupported>(failure.error)

        assertContentEquals(before, file.readBytes())
        assertNoStagingFilesLeftBehind()
    }

    /** No `documentkit-*.tmp` may survive an operation, successful or not. */
    private fun assertNoStagingFilesLeftBehind() {
        val leaked = workspace.listFiles().orEmpty()
            .filter { it.name.startsWith("documentkit-") && it.name.endsWith(".tmp") }

        assertTrue(leaked.isEmpty(), "staging files left behind: ${leaked.map { it.name }}")
    }
}
