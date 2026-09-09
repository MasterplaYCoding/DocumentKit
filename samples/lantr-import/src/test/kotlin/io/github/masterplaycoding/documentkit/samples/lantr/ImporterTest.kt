package io.github.masterplaycoding.documentkit.samples.lantr

import io.github.masterplaycoding.documentkit.AssetId
import io.github.masterplaycoding.documentkit.io.DocumentStore
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ImporterTest {

    private lateinit var workspace: File

    @BeforeTest
    fun setUp() {
        workspace = File.createTempFile("lantr-import-", "").apply {
            delete()
            mkdirs()
        }
    }

    @AfterTest
    fun tearDown() {
        workspace.deleteRecursively()
    }

    private fun fixture(name: String = "talk.ltrn"): File =
        LtrnFixture.complete(File(workspace, name)).build()

    @Test
    fun preservesTheContentAReaderWouldRecognise() {
        val report = LtrnImporter.import(fixture())
        val presentation = report.presentation

        assertEquals("Extracting a persistence layer", presentation.title)
        assertEquals("Matei Ursache", presentation.author)
        assertEquals(2, presentation.sections.size)

        // Section order follows arc/blocks.json, not file order.
        assertEquals(listOf("The problem", "The result"), presentation.sections.map { it.name })
        assertEquals(listOf(2, 1), presentation.sections.map { it.slides.size })

        val text = presentation.sections
            .flatMap { it.slides }
            .flatMap { it.blocks }
            .filter { it.kind == "text" }
            .mapNotNull { it.text }
        assertTrue(text.any { it.contains("The behaviours did not") }, "lost slide text: $text")
    }

    @Test
    fun carriesImagesAcrossAsAssets() {
        val report = LtrnImporter.import(fixture())

        assertEquals(setOf(AssetId.of("diagram.png")), report.assets.keys)

        val referenced = report.presentation.sections
            .flatMap { it.slides }
            .flatMap { it.blocks }
            .filter { it.kind == "image" }
            .mapNotNull { it.assetId }
        assertEquals(listOf("diagram.png"), referenced)
    }

    @Test
    fun namesEverythingItDidNotCarry() {
        val report = LtrnImporter.import(fixture())
        val described = report.unsupported.map { "${it.where}: ${it.what}" }

        // The list is the deliverable. A converter that silently drops two
        // thirds of a file and reports success has told the user something
        // false.
        assertTrue(report.unsupported.isNotEmpty())
        for (expected in listOf(
            "metadata.json: duration",
            "metadata.json: audience",
            "metadata.json: purpose",
        )) {
            assertTrue(described.contains(expected), "missing '$expected' in $described")
        }

        assertTrue(described.any { it.startsWith("theme.json") }, described.toString())
        assertTrue(described.any { it.contains("rhetoric scoring") }, described.toString())
        assertTrue(described.any { it.contains("colour override") }, described.toString())
        assertTrue(described.any { it.contains("corner radius") }, described.toString())
    }

    @Test
    fun reportsAnImageReferenceWithNoFileBehindIt() {
        val target = LtrnFixture(File(workspace, "dangling.ltrn"))
            .entry("metadata.json", """{"title":"Dangling"}""")
            .entry("arc/blocks.json", """{"block_ids":["b"]}""")
            .entry(
                "arc/blocks/block_001.json",
                """{"id":"b","name":"S","main_idea":"","slide_ids":["s"]}""",
            )
            .entry(
                "slides/slide_001.json",
                """{"id":"s","blocks":[{"block_type":"image","image_path":"absent.png"}]}""",
            )
            .build()

        val report = LtrnImporter.import(target)

        // Reported and skipped, not written as a dangling asset id - which the
        // codec would reject on open anyway, failing later and less clearly.
        assertTrue(
            report.unsupported.any { it.what.contains("absent.png") },
            report.unsupported.toString(),
        )
        assertTrue(report.presentation.sections.single().slides.single().blocks.isEmpty())
    }

    @Test
    fun reportsAnImageWhoseNameCannotBeAnAssetId() {
        val target = LtrnFixture(File(workspace, "oddname.ltrn"))
            .entry("metadata.json", """{"title":"Odd"}""")
            .entry("arc/blocks.json", """{"block_ids":[]}""")
            .entry("images/a picture.png", ByteArray(16))
            .build()

        val report = LtrnImporter.import(target)

        // Renaming a user's asset without telling them breaks the reference in
        // a way nobody can trace, so it is skipped and named instead.
        assertTrue(report.assets.isEmpty())
        assertTrue(
            report.unsupported.any { it.what.contains("not a valid asset id") },
            report.unsupported.toString(),
        )
    }

    @Test
    fun reportsBlocksOfATypeItDoesNotUnderstand() {
        val target = LtrnFixture(File(workspace, "future.ltrn"))
            .entry("metadata.json", """{"title":"Future"}""")
            .entry("arc/blocks.json", """{"block_ids":["b"]}""")
            .entry("arc/blocks/block_001.json", """{"id":"b","name":"S","slide_ids":["s"]}""")
            .entry(
                "slides/slide_001.json",
                """{"id":"s","blocks":[{"block_type":"video","src":"clip.mp4"}]}""",
            )
            .build()

        val report = LtrnImporter.import(target)

        assertTrue(
            report.unsupported.any { it.what.contains("unsupported type 'video'") },
            report.unsupported.toString(),
        )
    }

    @Test
    fun theResultOpensAsARealDocumentKitContainer() = runTest {
        val source = fixture()
        val before = source.readBytes()

        val report = LtrnImporter.import(source)
        val destination = File(workspace, "talk.dkit")
        val store = DocumentStore()

        store.save(
            destination = destination,
            document = report.presentation,
            documentId = "talk",
            codec = importedPresentationCodec,
            assets = report.assets,
        )

        store.open(destination, importedPresentationCodec).use { opened ->
            assertEquals("Extracting a persistence layer", opened.document.title)
            assertEquals(2, opened.document.sections.size)
            assertEquals(4096, opened.readAsset(AssetId.of("diagram.png")).size)
        }

        // The source is read-only, always. There is no .ltrn writer and no
        // round trip: this deliberately discards most of what Lantr stores.
        assertContentEquals(before, source.readBytes())
    }
}
