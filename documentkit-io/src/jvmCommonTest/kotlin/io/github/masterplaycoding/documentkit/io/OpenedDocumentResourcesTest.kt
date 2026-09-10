package io.github.masterplaycoding.documentkit.io

import io.github.masterplaycoding.documentkit.AssetId
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * "An opened document owns its resources and releases them on close."
 *
 * The README has promised this since 0.1 and nothing checked it. The promise is
 * not decorative: on Windows an open ZIP handle locks the file, so a leak does
 * not show up as memory growth but as a save that cannot replace the document
 * the user is editing. Open, edit, save is the entire lifecycle of an editor,
 * and it is the exact sequence a leaked handle breaks.
 *
 * These run on Linux, Windows and macOS in CI, which matters more here than
 * almost anywhere else in the suite: on Linux a leaked handle passes every one
 * of these assertions, because deleting an open file is allowed.
 */
class OpenedDocumentResourcesTest {

    private lateinit var workspace: File
    private val store = DocumentStore()

    @BeforeTest
    fun setUp() {
        workspace = File.createTempFile("documentkit-resources-", "").apply {
            delete()
            mkdirs()
        }
    }

    @AfterTest
    fun tearDown() {
        workspace.deleteRecursively()
    }

    private val cover = ByteArray(4096) { (it % 251).toByte() }

    private suspend fun saved(name: String = "doc.dkit", title: String = "Original"): File {
        val file = File(workspace, name)
        store.save(
            destination = file,
            document = Notebook(title),
            documentId = "doc-1",
            codec = notebookCodec,
            assets = mapOf(AssetId.of("cover") to AssetSource.ofBytes(cover)),
        )
        return file
    }

    @Test
    fun theFileCanBeDeletedAfterTheHandleIsClosed() = runTest {
        val file = saved()

        store.open(file, notebookCodec).use { assertEquals("Original", it.document.title) }

        assertTrue(file.delete(), "a closed handle must not still hold the file")
        assertTrue(!file.exists())
    }

    @Test
    fun theDocumentCanBeSavedOverAfterReading() = runTest {
        // Open, edit, save. The lifecycle of every editor this library exists
        // for, and the one a leaked handle breaks on Windows.
        val file = saved()

        store.open(file, notebookCodec).use { assertEquals("Original", it.document.title) }

        store.save(
            destination = file,
            document = Notebook("Edited"),
            documentId = "doc-1",
            codec = notebookCodec,
            assets = mapOf(AssetId.of("cover") to AssetSource.ofBytes(cover)),
        )

        store.open(file, notebookCodec).use { assertEquals("Edited", it.document.title) }
    }

    @Test
    fun useReleasesTheHandleEvenWhenTheBlockThrows() = runTest {
        val file = saved()

        assertFailsWith<IllegalStateException> {
            store.open(file, notebookCodec).use { error("the application gave up") }
        }

        // The failure was the caller's. The handle is still the library's
        // responsibility, and an exception is precisely when a leak is easiest
        // to introduce and hardest to notice.
        assertTrue(file.delete(), "use { } must release the handle on the failure path too")
    }

    @Test
    fun readingAnAssetAfterCloseFailsClearly() = runTest {
        val file = saved()
        val opened = store.open(file, notebookCodec)
        opened.close()

        // Not a ZipException from somewhere inside java.util.zip, and not
        // silently empty bytes: a caller who kept the handle too long should be
        // told exactly that.
        val failure = assertFailsWith<IllegalStateException> { opened.openAsset(AssetId.of("cover")) }
        assertTrue(failure.message?.contains("closed") == true, "should say it is closed: $failure")
    }

    @Test
    fun closingTwiceIsHarmless() = runTest {
        val file = saved()
        val opened = store.open(file, notebookCodec)

        opened.close()
        opened.close()

        assertTrue(file.delete())
    }

    @Test
    fun twoIndependentHandlesOnOneFileBothWork() = runTest {
        // Documented as fine, and worth pinning: the limit is one handle used
        // concurrently, not one handle at a time.
        val file = saved()

        store.open(file, notebookCodec).use { first ->
            store.open(file, notebookCodec).use { second ->
                assertEquals(first.document, second.document)
                assertContentEquals(
                    first.readAsset(AssetId.of("cover")),
                    second.readAsset(AssetId.of("cover")),
                )
            }
        }

        assertTrue(file.delete(), "both handles must have released the file")
    }

    @Test
    fun manyOpenAndCloseCyclesLeakNothing() = runTest {
        // A leak of one handle is invisible; a leak per open is what exhausts a
        // process. Fifty cycles is enough to make a per-open leak obvious while
        // costing nothing to run.
        val file = saved()

        repeat(50) {
            store.open(file, notebookCodec).use { assertEquals("Original", it.document.title) }
        }

        assertTrue(file.delete())
    }

    @Test
    fun aFailedOpenLeavesNothingHoldingTheFile() = runTest {
        // The path where a leak is likeliest: the archive is opened, something
        // is found wrong, and an exception leaves before anyone thought about
        // the handle.
        val file = File(workspace, "damaged.dkit")
        val whole = saved("source.dkit").readBytes()
        file.writeBytes(whole.copyOf(whole.size / 2))

        assertFailsWith<Throwable> { store.open(file, notebookCodec) }

        assertTrue(file.delete(), "a failed open must not leave the file held")
    }
}
