package io.github.masterplaycoding.documentkit.android

import android.app.Application
import android.net.Uri
import io.github.masterplaycoding.documentkit.AssetId
import io.github.masterplaycoding.documentkit.DocumentCodec
import io.github.masterplaycoding.documentkit.DocumentError
import io.github.masterplaycoding.documentkit.DocumentException
import io.github.masterplaycoding.documentkit.DocumentLimits
import io.github.masterplaycoding.documentkit.io.AssetSource
import io.github.masterplaycoding.documentkit.io.DocumentStore
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Supplier
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Storage Access Framework import and export, at the lowest and highest API
 * levels the library supports.
 *
 * These run under Robolectric: real Android framework code for each SDK named
 * below, on the JVM. That is not a device - a real provider app, real storage
 * and real process death are out of reach - but it is the actual
 * `ContentResolver`, `ParcelFileDescriptor` and file-descriptor plumbing each
 * API level ships, which is what `DocumentTransfer` talks to. Where a provider's
 * *stream* has to misbehave partway through, Robolectric's registered streams
 * stand in, since a file descriptor cannot be made to fail on cue.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 36])
class DocumentTransferTest {

    private lateinit var context: Application
    private lateinit var root: File
    private lateinit var transfer: DocumentTransfer

    @BeforeTest
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        root = File(context.filesDir, "provider-root").apply { mkdirs() }
        TestDocumentProvider.root = root
        Robolectric.setupContentProvider(TestDocumentProvider::class.java, TestDocumentProvider.AUTHORITY)
        transfer = DocumentTransfer(context)
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    // --- fixtures -------------------------------------------------------------

    private val cover = ByteArray(10_000) { (it % 251).toByte() }

    private fun codec(validate: (JsonObject) -> String? = { null }) = DocumentCodec(
        applicationId = "test.transfer",
        schemaVersion = 1,
        serializer = JsonObject.serializer(),
        referencedAssets = { document ->
            setOfNotNull((document["image"] as? JsonPrimitive)?.content?.let(AssetId::of))
        },
        validate = validate,
    )

    private fun notebook(title: String, image: String? = null) = buildJsonObject {
        put("title", title)
        if (image != null) put("image", image)
    }

    private fun stagingFiles(): List<File> =
        File(context.cacheDir, "documentkit-staging").listFiles().orEmpty().toList()

    private suspend fun exported(name: String): Uri {
        val uri = TestDocumentProvider.uri("docs/$name")
        transfer.exportCopy(
            uri,
            notebook("Field notes", image = "cover"),
            "doc-1",
            codec(),
            mapOf(AssetId.of("cover") to AssetSource.ofBytes(cover)),
        )
        return uri
    }

    // --- through a provider, the path a device takes ----------------------------

    @Test
    fun exportThenImportRoundTripsTheDocumentAndItsAssets() = runTest {
        val uri = TestDocumentProvider.uri("docs/notes.dkit")

        val receipt = transfer.exportCopy(
            uri,
            notebook("Field notes", image = "cover"),
            "doc-1",
            codec(),
            mapOf(AssetId.of("cover") to AssetSource.ofBytes(cover)),
        )

        assertEquals(uri.toString(), receipt.uri)
        assertEquals(TestDocumentProvider.fileFor(uri).length(), receipt.bytesWritten)

        transfer.import(uri, codec()).use { opened ->
            assertEquals(JsonPrimitive("Field notes"), opened.document["title"])
            assertContentEquals(cover, opened.readAsset(AssetId.of("cover")))
        }
    }

    @Test
    fun theImportedHandleOwnsItsStagingCopyAndDeletesItOnClose() = runTest {
        val uri = exported("owned.dkit")

        transfer.import(uri, codec()).use {
            assertEquals(1, stagingFiles().size, "the open handle reads from one private copy")
        }

        assertEquals(emptyList(), stagingFiles())
    }

    @Test
    fun exportReplacesALongerDocumentWithoutLeavingItsTail() = runTest {
        // "wt", not "w": without truncation a provider may overwrite the start
        // of a longer file and keep the rest, and the result is a ZIP with the
        // old document's bytes after its end - which some readers accept and
        // some do not.
        //
        // This is where testing two API levels earns its keep. Swapping in
        // "w" fails this at API 36 and passes it at API 24, because older
        // Android truncated on plain "w" and newer Android does not: an
        // export written against an old device would pass every test there
        // and corrupt files on a new one.
        val uri = TestDocumentProvider.uri("docs/longer.dkit")
        TestDocumentProvider.fileFor(uri).writeBytes(ByteArray(1024 * 1024) { 0x55 })

        val receipt = transfer.exportCopy(uri, notebook("Short"), "doc-1", codec())

        assertEquals(receipt.bytesWritten, TestDocumentProvider.fileFor(uri).length())
        transfer.import(uri, codec()).use { assertEquals(JsonPrimitive("Short"), it.document["title"]) }
    }

    @Test
    fun exportVerifiesBeforeTheDestinationIsOpened() = runTest {
        // The README's guarantee row. Opening the provider's stream truncates
        // the destination, so a document that was going to fail must fail
        // before that - or the user's existing file is gone and nothing
        // replaced it.
        val uri = TestDocumentProvider.uri("docs/precious.dkit")
        val precious = "the user's existing file".toByteArray()
        TestDocumentProvider.fileFor(uri).writeBytes(precious)

        val failure = assertFailsWith<DocumentException> {
            transfer.exportCopy(uri, notebook(""), "doc-1", codec { doc ->
                if ((doc["title"] as JsonPrimitive).content.isBlank()) "a notebook needs a title" else null
            })
        }

        assertContains(failure.message.orEmpty(), "needs a title")
        assertContentEquals(precious, TestDocumentProvider.fileFor(uri).readBytes())
        assertEquals(emptyList(), stagingFiles())
    }

    @Test
    fun aProviderWithNoInputStreamIsAStructuredError() = runTest {
        val failure = assertFailsWith<DocumentException> {
            transfer.import(TestDocumentProvider.uri("null/x.dkit"), codec())
        }

        val error = assertIs<DocumentError.IoFailure>(failure.error)
        assertContains(error.toString(), "no input stream")
        assertEquals(emptyList(), stagingFiles())
    }

    @Test
    fun aProviderWithNoOutputStreamIsAStructuredError() = runTest {
        val failure = assertFailsWith<DocumentException> {
            transfer.exportCopy(TestDocumentProvider.uri("null/x.dkit"), notebook("A"), "doc-1", codec())
        }

        val error = assertIs<DocumentError.IoFailure>(failure.error)
        assertContains(error.toString(), "no output stream")
        // The verified archive was built before the provider said no.
        assertEquals(emptyList(), stagingFiles())
    }

    @Test
    fun aRevokedPermissionReachesTheCallerAsSecurityException() = runTest {
        // Passed through unwrapped, as the KDoc says: an application catches
        // this specifically, to ask the user to pick the file again.
        assertFailsWith<SecurityException> {
            transfer.import(TestDocumentProvider.uri("denied/x.dkit"), codec())
        }
        assertFailsWith<SecurityException> {
            transfer.exportCopy(TestDocumentProvider.uri("denied/x.dkit"), notebook("A"), "doc-1", codec())
        }
        assertEquals(emptyList(), stagingFiles())
    }

    @Test
    fun aDeletedDocumentReachesTheCallerAsFileNotFound() = runTest {
        assertFailsWith<FileNotFoundException> {
            transfer.import(TestDocumentProvider.uri("missing/x.dkit"), codec())
        }
        assertEquals(emptyList(), stagingFiles())
    }

    // --- streams that fail partway ---------------------------------------------

    private fun registerInput(uri: Uri, stream: () -> InputStream) =
        shadowOf(context.contentResolver).registerInputStreamSupplier(uri, Supplier { stream() })

    private fun registerOutput(uri: Uri, stream: () -> OutputStream) =
        shadowOf(context.contentResolver).registerOutputStreamSupplier(uri, Supplier { stream() })

    @Test
    fun anImportThatFailsMidwayLeavesNoStagingCopy() = runTest {
        val uri = TestDocumentProvider.uri("flaky/x.dkit")
        registerInput(uri) {
            object : InputStream() {
                var served = 0
                override fun read(): Int {
                    if (served++ >= 5_000) throw IOException("connection to the provider dropped")
                    return 0x41
                }
            }
        }

        val failure = assertFailsWith<IOException> { transfer.import(uri, codec()) }

        assertContains(failure.message.orEmpty(), "dropped")
        assertEquals(emptyList(), stagingFiles())
    }

    @Test
    fun anEndlessProviderStreamStopsAtTheArchiveLimit() = runTest {
        // A provider is outside the app's control. One serving bytes forever
        // must fill a bounded file and fail, not fill the device.
        val limit = 256L * 1024
        val bounded = DocumentTransfer(
            context,
            DocumentStore(DocumentLimits.Default.copy(maxArchiveBytes = limit)),
        )
        val uri = TestDocumentProvider.uri("endless/x.dkit")
        var served = 0L
        registerInput(uri) {
            object : InputStream() {
                override fun read(): Int = 0.also { served++ }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    served += length
                    return length
                }
            }
        }

        val failure = assertFailsWith<DocumentException> { bounded.import(uri, codec()) }

        assertIs<DocumentError.LimitExceeded>(failure.error)
        // Read at most one buffer past the limit, then stopped.
        assertTrue(served <= limit + 64 * 1024, "read $served bytes against a $limit-byte limit")
        assertEquals(emptyList(), stagingFiles())
    }

    @Test
    fun anExportThatFailsMidwaySaysTheDestinationMayBePartial() = runTest {
        // Past the point of no return, "failed" alone would be a lie of
        // omission: the destination was truncated and holds part of a file.
        val uri = TestDocumentProvider.uri("flaky/x.dkit")
        registerOutput(uri) {
            object : OutputStream() {
                var written = 0
                override fun write(byte: Int) {
                    if (written++ >= 100) throw IOException("provider stopped accepting data")
                }
            }
        }

        val failure = assertFailsWith<DocumentException> {
            transfer.exportCopy(
                uri,
                notebook("Field notes", image = "cover"),
                "doc-1",
                codec(),
                mapOf(AssetId.of("cover") to AssetSource.ofBytes(cover)),
            )
        }

        assertContains(failure.message.orEmpty(), "may contain partial content")
        assertEquals(emptyList(), stagingFiles())
    }

    // --- cancellation -----------------------------------------------------------

    @Test
    fun aCancelledImportLeavesNothingBehind() = runTest {
        // The codec's own validation cancels the caller: it runs inside the
        // open, nothing after it suspends, so the open completes and the
        // cancellation is only noticed on the way out - where withContext
        // used to discard the finished handle, and the staging copy it owned.
        val uri = exported("cancelled.dkit")
        val caller = AtomicReference<Job>()
        val cancelling = codec { _ ->
            caller.get().cancel()
            null
        }

        coroutineScope {
            val job = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
                transfer.import(uri, cancelling)
            }
            caller.set(job)
            job.start()
            job.join()
            assertTrue(job.isCancelled, "the scenario relies on the caller being cancelled")
        }

        assertEquals(emptyList(), stagingFiles())
    }

    @Test
    fun aCancelledExportLeavesNothingBehind() = runTest {
        // Export verifies its archive by opening it with the codec, so the
        // same trick lands the cancellation after the archive is built.
        val uri = TestDocumentProvider.uri("docs/cancelled-export.dkit")
        val caller = AtomicReference<Job>()
        val cancelling = codec { _ ->
            caller.get().cancel()
            null
        }

        coroutineScope {
            val job = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
                transfer.exportCopy(uri, notebook("A"), "doc-1", cancelling)
            }
            caller.set(job)
            job.start()
            job.join()
            assertTrue(job.isCancelled)
        }

        assertEquals(emptyList(), stagingFiles())
        assertTrue(!TestDocumentProvider.fileFor(uri).exists(), "a cancelled export opened the destination")
    }

    // --- housekeeping -----------------------------------------------------------

    @Test
    fun cleaningTheStagingDirectoryTouchesOnlyItsOwnFiles() = runTest {
        // What a cold start after process death calls. Scanning the whole cache
        // for files that look stale would eventually delete someone else's.
        val staging = File(context.cacheDir, "documentkit-staging").apply { mkdirs() }
        File(staging, "import-left-by-a-killed-process.dkit").writeText("stale")
        val neighbour = File(context.cacheDir, "belongs-to-the-app.tmp").apply { writeText("keep") }

        assertEquals(1, transfer.cleanStagingDirectory())
        assertEquals(emptyList(), stagingFiles())
        assertTrue(neighbour.exists())
    }
}
