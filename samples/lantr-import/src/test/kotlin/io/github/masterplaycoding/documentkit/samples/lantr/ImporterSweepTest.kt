package io.github.masterplaycoding.documentkit.samples.lantr

import io.github.masterplaycoding.documentkit.io.DocumentStore
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Every single-field change to every JSON file a `.ltrn` holds, tried
 * deterministically, the way documentkit-io's FieldSweepTest treats a
 * container. That class lives in another module's test sources, so its edge
 * values are repeated here rather than shared.
 *
 * A `.ltrn` is somebody else's file, so every field in it is untrusted. The
 * importer's own promise is the oracle:
 *
 * - **It does not crash.** Nothing escapes [LtrnImporter.import] except its
 *   refusal of an archive that is not a Lantr file at all.
 * - **It loses nothing silently.** The importer's point is that it names what
 *   it did not carry. When a change makes a section, slide or block disappear
 *   compared with the unchanged file, the report must say something it did
 *   not say before.
 * - **What it imports is usable.** A presentation it returns must save as a
 *   DocumentKit container and reopen, which is the only thing it is for.
 *
 * The second rule is deliberately weak: it asks for *a* new report item, not
 * the right one. It catches the silent drop, which is the failure that tells
 * the user something false.
 */
class ImporterSweepTest {

    private lateinit var workspace: File
    private val store = DocumentStore()
    private val findings = linkedMapOf<String, String>()

    @BeforeTest
    fun setUp() {
        workspace = File.createTempFile("lantr-sweep-", "").apply {
            delete()
            mkdirs()
        }
        findings.clear()
    }

    @AfterTest
    fun tearDown() {
        workspace.deleteRecursively()
    }

    private companion object {
        // Markers replaced after serialising: JSON a serialiser will not emit.
        const val INFINITY = "__SWEEP_INFINITY__"
        const val NEGATIVE_ZERO = "__SWEEP_NEGATIVE_ZERO__"
        const val BEYOND_LONG = "__SWEEP_BEYOND_LONG__"

        /** The same edge values as documentkit-io's FieldSweepTest. */
        val EDGES: List<JsonElement> = listOf(
            JsonNull,
            JsonPrimitive(true),
            JsonPrimitive(false),
            JsonPrimitive(0),
            JsonPrimitive(-1),
            JsonPrimitive(1),
            JsonPrimitive(1.5),
            JsonPrimitive(2147483648L),
            JsonPrimitive(Long.MAX_VALUE),
            JsonPrimitive(INFINITY),
            JsonPrimitive(NEGATIVE_ZERO),
            JsonPrimitive(BEYOND_LONG),
            JsonPrimitive(""),
            JsonPrimitive(" "),
            JsonPrimitive("x"),
            JsonPrimitive("../escape"),
            JsonPrimitive("0".repeat(64)),
            JsonPrimitive("a".repeat(10_000)),
            JsonArray(emptyList()),
            JsonObject(emptyMap()),
        )
    }

    /** Every path in [element]: object keys and array indexes, the root included. */
    private fun paths(element: JsonElement, prefix: List<Any> = emptyList()): List<List<Any>> = listOf(prefix) +
        when (element) {
            is JsonObject -> element.entries.flatMap { (key, child) -> paths(child, prefix + key) }
            is JsonArray -> element.flatMapIndexed { index, child -> paths(child, prefix + index) }
            else -> emptyList()
        }

    /** [element] with the value at [path] replaced by [value], or removed when [value] is null. */
    private fun edited(element: JsonElement, path: List<Any>, value: JsonElement?): JsonElement {
        if (path.isEmpty()) return value ?: element
        val head = path.first()
        val rest = path.drop(1)
        return when (element) {
            is JsonObject -> {
                val key = head as String
                val child = element[key] ?: return element
                if (rest.isEmpty() && value == null) JsonObject(element - key)
                else JsonObject(element + (key to edited(child, rest, value)))
            }
            is JsonArray -> {
                val index = head as Int
                if (index !in element.indices) return element
                if (rest.isEmpty() && value == null) JsonArray(element.filterIndexed { i, _ -> i != index })
                else JsonArray(element.mapIndexed { i, child -> if (i == index) edited(child, rest, value) else child })
            }
            else -> element
        }
    }

    private fun bytesOf(element: JsonElement): ByteArray = element.toString()
        .replace("\"$INFINITY\"", "1e999")
        .replace("\"$NEGATIVE_ZERO\"", "-0")
        .replace("\"$BEYOND_LONG\"", "9223372036854775808")
        .toByteArray()

    /** How much content an import carried: sections, slides, blocks. */
    private data class Carried(val sections: Int, val slides: Int, val blocks: Int)

    private fun carried(presentation: ImportedPresentation): Carried {
        val slides = presentation.sections.flatMap { it.slides }
        return Carried(presentation.sections.size, slides.size, slides.sumOf { it.blocks.size })
    }

    private fun record(signature: String, label: String) {
        findings.putIfAbsent(signature, label)
    }

    /** The path with indexes generalised, so one bug reached many ways is one finding. */
    private fun shapeOf(path: List<Any>): String =
        path.joinToString(".") { if (it is Int) "*" else it.toString() }.ifEmpty { "(root)" }

    /**
     * @param refusalAllowed the change made `metadata.json` unusable as a whole,
     *   so [LtrnImportException] is the documented answer rather than a crash.
     * @param blocksMayShrink the change removed blocks from the input itself -
     *   deleting `blocks` or one of its elements, or setting it to null or an
     *   empty list - so fewer blocks out is not a loss.
     */
    private fun examine(
        entries: List<Pair<String, ByteArray>>,
        where: String,
        label: String,
        baseline: ImportReport,
        refusalAllowed: Boolean = false,
        blocksMayShrink: Boolean = false,
    ) {
        val source = LtrnFixture(File(workspace, "case.ltrn"))
            .apply { for ((name, bytes) in entries) entry(name, bytes) }
            .build()

        val report = try {
            LtrnImporter.import(source)
        } catch (cause: Exception) {
            if (refusalAllowed && cause is LtrnImportException) return
            val frame = cause.stackTrace.firstOrNull { it.className.contains("samples.lantr") }
            record("$where: ${cause::class.simpleName} escaped import (${frame?.methodName}:${frame?.lineNumber})", label)
            return
        }

        val before = carried(baseline.presentation)
        val after = carried(report.presentation)
        val lost = listOfNotNull(
            "a section".takeIf { after.sections < before.sections },
            "a slide".takeIf { after.slides < before.slides },
            "a block".takeIf { after.blocks < before.blocks && !blocksMayShrink },
        )
        val newlyReported = report.unsupported.toSet() - baseline.unsupported.toSet()
        if (lost.isNotEmpty() && newlyReported.isEmpty()) {
            record("$where: ${lost.joinToString(" and ")} dropped without being reported", label)
        }

        try {
            runBlocking {
                val destination = File(workspace, "case.dkit")
                store.save(destination, report.presentation, "case", importedPresentationCodec, report.assets)
                store.open(destination, importedPresentationCodec).use { }
            }
        } catch (cause: Exception) {
            record("$where: an import that succeeded could not be saved (${cause::class.simpleName})", label)
        }
    }

    @Test
    fun everySingleFieldChangeToEveryJsonFile() {
        val seed = LtrnFixture.complete(File(workspace, "seed.ltrn"))
        val entries = seed.contents()
        val baseline = LtrnImporter.import(seed.build())

        var cases = 0
        var expectedCases = 0
        for ((name, bytes) in entries) {
            if (!name.endsWith(".json")) continue
            val original = Json.parseToJsonElement(bytes.decodeToString())
            val allPaths = paths(original)
            expectedCases += allPaths.size * (EDGES.size + 1) + 1

            fun replaced(changed: ByteArray) = entries.map { (n, b) -> if (n == name) n to changed else n to b }

            for (path in allPaths) {
                for (edge in listOf<Int?>(null) + EDGES.indices) {
                    val changed = bytesOf(edited(original, path, edge?.let { EDGES[it] }))
                    val label = "$name ${path.joinToString(".").ifEmpty { "(root)" }} ${edge?.let { "edge $it" } ?: "deleted"}"
                    val value = edge?.let { EDGES[it] }
                    val removesBlocks = name.startsWith("slides/") && (
                        (path == listOf("blocks") && (value == null || value == JsonNull || value == JsonArray(emptyList()))) ||
                            (path.size == 2 && path[0] == "blocks" && value == null)
                        )
                    examine(
                        replaced(changed),
                        "$name ${shapeOf(path)}",
                        label,
                        baseline,
                        refusalAllowed = name == "metadata.json" && path.isEmpty(),
                        blocksMayShrink = removesBlocks,
                    )
                    cases += 1
                }
            }

            // And the file not being JSON at all.
            examine(
                replaced("{".toByteArray()),
                "$name (not JSON)",
                "$name not JSON",
                baseline,
                refusalAllowed = name == "metadata.json",
            )
            cases += 1
        }

        assertEquals(expectedCases, cases)
        assertTrue(
            findings.isEmpty(),
            "$cases cases: ${findings.size} distinct finding(s)\n" +
                findings.entries.joinToString("\n") { (signature, label) -> "  - $signature  [$label]" },
        )
    }
}
