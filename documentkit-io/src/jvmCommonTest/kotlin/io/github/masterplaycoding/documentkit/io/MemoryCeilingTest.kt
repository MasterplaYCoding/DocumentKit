package io.github.masterplaycoding.documentkit.io

import io.github.masterplaycoding.documentkit.AssetId
import io.github.masterplaycoding.documentkit.DocumentLimits
import java.io.File
import java.io.InputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The claim: an asset costs a buffer, not its own size in heap.
 *
 * It is the reason [AssetSource] is a stream factory rather than a
 * `ByteArray`, the reason the reader streams entries instead of extracting
 * them, and it is stated in the README. Nothing verified it, and it is the
 * kind of property that a single well-meaning `readBytes()` in the wrong place
 * quietly removes - every other test in this repository would still pass,
 * because they all use assets small enough to buffer without anyone noticing.
 *
 * So this test does not measure anything. It runs the streaming paths over an
 * asset far larger than the heap it is given, in a JVM launched with that heap
 * (see the `memoryCeilingTest` task). If any stage of save or read materialises
 * the asset, the run dies with an OutOfMemoryError, which is a considerably
 * clearer signal than a number that drifted.
 *
 * Nothing large touches the disk. The bytes are generated on demand and are
 * repetitive, so deflate reduces the archive to a few hundred kilobytes; what
 * is large is only ever the stream passing through.
 */
class MemoryCeilingTest {

    private lateinit var workspace: File

    @BeforeTest
    fun setUp() {
        workspace = File.createTempFile("documentkit-memory-", "").apply {
            delete()
            mkdirs()
        }
    }

    @AfterTest
    fun tearDown() {
        workspace.deleteRecursively()
    }

    /** Bytes without a byte array: a stream that generates as it is read. */
    private class GeneratedStream(private var remaining: Long) : InputStream() {
        private var position = 0L

        override fun read(): Int {
            if (remaining <= 0) return -1
            remaining -= 1
            return ((position++) % 251).toInt()
        }

        override fun read(destination: ByteArray, offset: Int, length: Int): Int {
            if (remaining <= 0) return -1
            val count = minOf(length.toLong(), remaining).toInt()
            for (index in 0 until count) {
                destination[offset + index] = ((position + index) % 251).toByte()
            }
            position += count
            remaining -= count
            return count
        }

        override fun available(): Int = minOf(remaining, Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * Far larger than the heap this runs in, and larger than the default
     * per-asset limit, so the limits are raised deliberately rather than the
     * asset shrunk to fit them.
     */
    private val assetBytes = 512L * 1024 * 1024

    private val store = DocumentStore(
        DocumentLimits(
            maxAssetBytes = assetBytes + 1,
            maxTotalUncompressedBytes = assetBytes + (1L * 1024 * 1024),
            maxArchiveBytes = assetBytes + (1L * 1024 * 1024),
        ),
    )

    @Test
    fun savesAndReadsAnAssetLargerThanTheHeap() = runTest {
        val heap = Runtime.getRuntime().maxMemory()
        assertTrue(
            heap < assetBytes,
            "this test proves nothing unless the heap is smaller than the asset: " +
                "heap ${heap / (1024 * 1024)} MiB, asset ${assetBytes / (1024 * 1024)} MiB. " +
                "Run it through the memoryCeilingTest task, which sets the heap.",
        )

        val destination = File(workspace, "large.dkit")

        store.save(
            destination = destination,
            document = Notebook("Large"),
            documentId = "doc-large",
            codec = notebookCodec,
            assets = mapOf(AssetId.of("video") to AssetSource { GeneratedStream(assetBytes) }),
        )

        // Compressible on purpose: the stream is huge, the file is not.
        assertTrue(
            destination.length() < 64L * 1024 * 1024,
            "the archive should be small; the size under test is the stream, " +
                "not the file (was ${destination.length()} bytes)",
        )

        store.open(destination, notebookCodec).use { opened ->
            assertEquals(assetBytes, opened.manifest.assets.single().length)

            // Drained, never held. This is what a streaming consumer does, and
            // readAsset - which returns a ByteArray - is deliberately not used
            // here: it would fail for a reason that says nothing about the
            // library.
            var counted = 0L
            opened.openAsset(AssetId.of("video")).use { stream ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    counted += read
                }
            }

            assertEquals(assetBytes, counted)
        }
    }

    @Test
    fun validatesAnArchiveLargerThanTheHeapWithoutHoldingIt() = runTest {
        // validate() digests every entry, which is the other place a whole
        // asset could end up in memory: hashing is streamed, or it is not.
        val destination = File(workspace, "validated.dkit")

        store.save(
            destination = destination,
            document = Notebook("Large"),
            documentId = "doc-large",
            codec = notebookCodec,
            assets = mapOf(AssetId.of("video") to AssetSource { GeneratedStream(assetBytes) }),
        )

        val report = store.validate(destination)

        assertTrue(report.isValid, "a container this build just wrote must validate: ${report.errors}")
    }
}
