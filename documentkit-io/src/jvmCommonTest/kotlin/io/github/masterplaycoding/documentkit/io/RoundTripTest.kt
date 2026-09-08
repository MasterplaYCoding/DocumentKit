package io.github.masterplaycoding.documentkit.io

import io.github.masterplaycoding.documentkit.AssetId
import io.github.masterplaycoding.documentkit.DocumentError
import io.github.masterplaycoding.documentkit.DocumentException
import io.github.masterplaycoding.documentkit.DocumentLimits
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class RoundTripTest {

    private lateinit var workspace: File
    private val store = DocumentStore()

    @BeforeTest
    fun setUp() {
        workspace = File.createTempFile("documentkit-test-", "").apply {
            delete()
            mkdirs()
        }
    }

    @AfterTest
    fun tearDown() {
        workspace.deleteRecursively()
    }

    private fun destination(name: String = "notebook.dkit") = File(workspace, name)

    @Test
    fun savesAndReopensAModelWithAnImage() = runTest {
        val image = ByteArray(4096) { (it % 251).toByte() }
        val notebook = Notebook(
            title = "Field notes",
            notes = listOf(
                Note("plain text"),
                Note("with a picture", imageAssetId = "cover"),
            ),
        )

        val file = destination()
        val receipt = store.save(
            destination = file,
            document = notebook,
            documentId = "doc-1",
            codec = notebookCodec,
            assets = mapOf(AssetId.of("cover") to AssetSource.ofBytes(image)),
        )

        assertIs<SaveReceipt.AtomicReplace>(receipt)
        assertTrue(receipt.bytesWritten > 0)

        store.open(file, notebookCodec).use { opened ->
            assertEquals(notebook, opened.document)
            assertEquals("doc-1", opened.manifest.documentId)
            assertEquals(2, opened.manifest.schemaVersion)
            assertTrue(opened.migrationsApplied.isEmpty())
            assertContentEquals(image, opened.readAsset(AssetId.of("cover")))
        }
    }

    @Test
    fun handlesUnicodeTextAndZeroByteAssets() = runTest {
        val notebook = Notebook(
            title = "Ünïcode — 日本語 ✅",
            notes = listOf(Note("emoji 💡", imageAssetId = "empty")),
        )

        val file = destination()
        store.save(
            destination = file,
            document = notebook,
            documentId = "doc-unicode",
            codec = notebookCodec,
            assets = mapOf(AssetId.of("empty") to AssetSource.ofBytes(ByteArray(0))),
        )

        store.open(file, notebookCodec).use { opened ->
            assertEquals(notebook, opened.document)
            assertEquals(0, opened.readAsset(AssetId.of("empty")).size)
        }
    }

    @Test
    fun savesADocumentWithNoAssetsAtAll() = runTest {
        val file = destination()
        store.save(file, Notebook("Empty"), "doc-empty", notebookCodec)

        store.open(file, notebookCodec).use { opened ->
            assertTrue(opened.assetIds.isEmpty())
            assertEquals("Empty", opened.document.title)
        }
    }

    @Test
    fun copiesAnAssetToACallerChosenDestination() = runTest {
        val image = ByteArray(1024) { 7 }
        val file = destination()
        store.save(
            destination = file,
            document = Notebook("With image", listOf(Note("n", "cover"))),
            documentId = "doc-2",
            codec = notebookCodec,
            assets = mapOf(AssetId.of("cover") to AssetSource.ofBytes(image)),
        )

        val extracted = File(workspace, "out/cover.bin")
        store.open(file, notebookCodec).use { it.copyAssetTo(AssetId.of("cover"), extracted) }

        assertContentEquals(image, extracted.readBytes())
    }

    @Test
    fun preservesAnIndexedButUnreferencedAsset() = runTest {
        // An older build of an application should not delete data a newer
        // build added, so assets not named by referencedAssets are kept.
        val file = destination()
        store.save(
            destination = file,
            document = Notebook("No references"),
            documentId = "doc-3",
            codec = notebookCodec,
            assets = mapOf(AssetId.of("orphan") to AssetSource.ofBytes(byteArrayOf(1, 2, 3))),
        )

        store.open(file, notebookCodec).use { opened ->
            assertEquals(setOf(AssetId.of("orphan")), opened.assetIds)
        }
    }

    @Test
    fun anUnrelatedModelUsesTheSameApi() = runTest {
        val file = destination("graph.dkit")
        store.save(
            destination = file,
            document = Diagram(nodes = listOf("a", "b"), edges = listOf("a->b")),
            documentId = "doc-diagram",
            codec = diagramCodec,
        )

        store.open(file, diagramCodec).use { opened ->
            assertEquals(listOf("a", "b"), opened.document.nodes)
        }
    }

    @Test
    fun refusesADocumentBelongingToAnotherApplication() = runTest {
        val file = destination()
        store.save(file, Diagram(), "doc-diagram", diagramCodec)

        val failure = assertFailsWith<DocumentException> { store.open(file, notebookCodec) }
        val error = assertIs<DocumentError.WrongApplication>(failure.error)
        assertEquals("example.diagram", error.found)
        assertEquals("example.notebook", error.expected)
    }

    @Test
    fun refusesToSaveAModelReferencingAnAbsentAsset() = runTest {
        val file = destination()

        val failure = assertFailsWith<DocumentException> {
            store.save(
                destination = file,
                document = Notebook("Dangling", listOf(Note("n", "missing"))),
                documentId = "doc-4",
                codec = notebookCodec,
            )
        }

        // A missing asset is never silently omitted on save. The staged
        // archive is verified exactly as an open would verify it, so a
        // document that could not be reopened never reaches the destination.
        assertIs<DocumentError.MissingReferencedAsset>(failure.error)
        assertTrue(!file.exists())
    }

    @Test
    fun runsTheApplicationsOwnValidation() = runTest {
        val file = destination()
        val failure = assertFailsWith<DocumentException> {
            store.save(file, Notebook(title = "  "), "doc-5", notebookCodec)
        }

        // The staged archive is verified before replacement, so an invalid
        // model fails the save rather than producing an unopenable file.
        assertIs<DocumentError.ApplicationValidationFailed>(failure.error)
        assertTrue(!file.exists())
    }

    @Test
    fun refusesAnAssetLargerThanTheConfiguredLimit() = runTest {
        val small = DocumentStore(DocumentLimits(maxAssetBytes = 1024))
        val file = destination()

        val failure = assertFailsWith<DocumentException> {
            small.save(
                destination = file,
                document = Notebook("Too big", listOf(Note("n", "huge"))),
                documentId = "doc-6",
                codec = notebookCodec,
                assets = mapOf(AssetId.of("huge") to AssetSource.ofBytes(ByteArray(4096))),
            )
        }

        // A writer applying weaker limits than its reader would produce a file
        // the same application could not open.
        assertIs<DocumentError.LimitExceeded>(failure.error)
        assertTrue(!file.exists())
    }
}
