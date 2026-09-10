package io.github.masterplaycoding.documentkit.io

import io.github.masterplaycoding.documentkit.AssetId
import io.github.masterplaycoding.documentkit.DocumentError
import io.github.masterplaycoding.documentkit.DocumentException
import java.io.ByteArrayInputStream
import java.io.File
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
 * [AssetSource]'s documented contract, and what happens when it is broken.
 *
 * The contract is unusual enough to be worth testing directly: `openStream` is
 * called **twice** during one save - once to measure and digest, once to write -
 * so a source must hand back a fresh stream each time. Nothing about the type
 * signature says so, and a one-shot source compiles perfectly.
 */
class AssetSourceTest {

    private lateinit var workspace: File
    private val store = DocumentStore()

    @BeforeTest
    fun setUp() {
        workspace = File.createTempFile("documentkit-asset-source-", "").apply {
            delete()
            mkdirs()
        }
    }

    @AfterTest
    fun tearDown() {
        workspace.deleteRecursively()
    }

    private fun destination(name: String = "doc.dkit") = File(workspace, name)

    // --- the factories ------------------------------------------------------

    @Test
    fun ofBytesReturnsAFreshStreamEveryTime() {
        val source = AssetSource.ofBytes(byteArrayOf(1, 2, 3))

        assertContentEquals(byteArrayOf(1, 2, 3), source.openStream().use { it.readBytes() })
        assertContentEquals(byteArrayOf(1, 2, 3), source.openStream().use { it.readBytes() })
    }

    @Test
    fun ofBytesCopiesSoALaterMutationCannotChangeTheAsset() {
        // The array belongs to the caller, who may reuse it as a buffer. A
        // source that kept the reference would save whatever it happened to
        // hold at write time, which is a data-loss bug wearing an optimisation
        // costume.
        val buffer = byteArrayOf(1, 2, 3)
        val source = AssetSource.ofBytes(buffer)

        buffer[0] = 99

        assertContentEquals(byteArrayOf(1, 2, 3), source.openStream().use { it.readBytes() })
    }

    @Test
    fun ofFileRereadsTheFileEachTime() {
        val file = File(workspace, "asset.bin").apply { writeBytes(byteArrayOf(7, 7)) }
        val source = AssetSource.ofFile(file)

        assertContentEquals(byteArrayOf(7, 7), source.openStream().use { it.readBytes() })
        assertContentEquals(byteArrayOf(7, 7), source.openStream().use { it.readBytes() })
    }

    @Test
    fun ofBytesHandlesAnEmptyAsset() {
        assertEquals(0, AssetSource.ofBytes(ByteArray(0)).openStream().use { it.readBytes() }.size)
    }

    // --- what happens when the contract is broken ---------------------------

    /** A source that can only be read once, which is what the contract forbids. */
    private class OneShot(bytes: ByteArray) : AssetSource {
        private val stream: InputStream = ByteArrayInputStream(bytes)
        var opens = 0
            private set

        override fun openStream(): InputStream {
            opens += 1
            // The same, already-drained stream. Compiles, type-checks, and is
            // exactly the mistake the KDoc warns about.
            return stream
        }
    }

    @Test
    fun aOneShotSourceFailsTheSaveRatherThanWritingAnEmptyAsset() = runTest {
        val source = OneShot(ByteArray(4096) { 3 })
        val file = destination()

        val failure = assertFailsWith<DocumentException> {
            store.save(
                destination = file,
                document = Notebook("Notes"),
                documentId = "doc-1",
                codec = notebookCodec,
                assets = mapOf(AssetId.of("video") to source),
            )
        }

        // The measure pass drained it; the write pass found nothing. Silently
        // shipping a zero-length asset whose manifest claims 4096 bytes would
        // produce a container that only fails when someone opens it - long
        // after the bytes it was supposed to preserve are gone.
        val error = assertIs<DocumentError.IoFailure>(failure.error)
        assertTrue(error.reason.contains("video"), "the error should name the asset: $error")
        assertEquals(2, source.opens, "both passes should have run")
    }

    @Test
    fun aFailedSaveLeavesNoFileBehind() = runTest {
        val file = destination()

        assertFailsWith<DocumentException> {
            store.save(
                destination = file,
                document = Notebook("Notes"),
                documentId = "doc-1",
                codec = notebookCodec,
                assets = mapOf(AssetId.of("video") to OneShot(ByteArray(1024) { 1 })),
            )
        }

        // Not a partial container, not a staging file left in the directory.
        assertTrue(!file.exists(), "a failed save must not create the destination")
        assertEquals(
            emptyList(),
            workspace.listFiles()?.map { it.name }?.sorted() ?: emptyList(),
            "a failed save must not leave staging files behind",
        )
    }

    @Test
    fun aPreviousDocumentSurvivesAFailedSave() = runTest {
        val file = destination()
        store.save(file, Notebook("Original"), "doc-1", notebookCodec)
        val before = file.readBytes()

        assertFailsWith<DocumentException> {
            store.save(
                destination = file,
                document = Notebook("Replacement"),
                documentId = "doc-1",
                codec = notebookCodec,
                assets = mapOf(AssetId.of("video") to OneShot(ByteArray(1024) { 1 })),
            )
        }

        // Byte for byte. The save that failed had no business touching it.
        assertContentEquals(before, file.readBytes())
        store.open(file, notebookCodec).use { assertEquals(Notebook("Original"), it.document) }
    }

    @Test
    fun aSourceWhoseContentChangesBetweenPassesFailsTheSave() = runTest {
        // Not a contract violation - each stream is fresh - but the bytes are
        // different, so the manifest built during the measure pass no longer
        // describes what was written. Same answer: refuse.
        var call = 0
        val shifting = AssetSource {
            call += 1
            ByteArrayInputStream(ByteArray(64) { if (call == 1) 1 else 2 })
        }

        val failure = assertFailsWith<DocumentException> {
            store.save(
                destination = destination(),
                document = Notebook("Notes"),
                documentId = "doc-1",
                codec = notebookCodec,
                assets = mapOf(AssetId.of("cover") to shifting),
            )
        }

        val error = assertIs<DocumentError.IoFailure>(failure.error)
        assertTrue(error.reason.contains("changed"), "should say the asset changed: $error")
    }

    @Test
    fun aSourceThatThrowsIsReportedRatherThanEscaping() = runTest {
        val exploding = AssetSource { error("the file was deleted under us") }

        val failure = assertFailsWith<Throwable> {
            store.save(
                destination = destination(),
                document = Notebook("Notes"),
                documentId = "doc-1",
                codec = notebookCodec,
                assets = mapOf(AssetId.of("cover") to exploding),
            )
        }

        // Whatever it is, the destination must not exist afterwards.
        assertTrue(failure is DocumentException || failure is IllegalStateException)
        assertTrue(!destination().exists())
    }
}
