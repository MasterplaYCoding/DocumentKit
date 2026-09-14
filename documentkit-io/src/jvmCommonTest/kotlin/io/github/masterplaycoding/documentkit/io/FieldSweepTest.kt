package io.github.masterplaycoding.documentkit.io

import io.github.masterplaycoding.documentkit.AssetId
import io.github.masterplaycoding.documentkit.DocumentKitFormat
import java.io.File
import java.util.zip.ZipFile
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
import kotlinx.serialization.json.jsonObject

/**
 * Every single-field change to a container's JSON, tried deterministically.
 *
 * [FuzzTest] searches at random, and random search is the wrong tool for a bug
 * that needs one particular field *and* one particular value. EventLab's saved-
 * plan fuzzer showed it: random-only, it missed a concurrency of 1.5 or Infinity
 * in 2,000 runs, because hitting it takes about one mutation in several hundred.
 * This sweep takes every path in `manifest.json` - which nothing digest-protects,
 * so every field of it is attacker-controlled - and every path in
 * `document.json`, re-digested so the change reaches the codec, and replaces
 * each value with every edge value below, and deletes it. Each result goes to
 * the same [ContractOracle] as the fuzzer.
 *
 * A few hundred cases per file, in a few seconds, on every build.
 */
class FieldSweepTest {

    private lateinit var workspace: File
    private val store = DocumentStore()
    private lateinit var oracle: ContractOracle

    @BeforeTest
    fun setUp() {
        workspace = File.createTempFile("documentkit-sweep-", "").apply {
            delete()
            mkdirs()
        }
        oracle = ContractOracle(store, workspace)
    }

    @AfterTest
    fun tearDown() {
        oracle.close()
        workspace.deleteRecursively()
    }

    private val cover = ByteArray(300) { (it * 7 % 256).toByte() }
    private val thumbnail = "tiny".toByteArray()
    private val notebook = Notebook(
        title = "Field notes",
        notes = listOf(Note("first"), Note("with a picture", imageAssetId = "cover")),
    )

    private suspend fun seedEntries(): List<Pair<String, ByteArray>> {
        val file = File(workspace, "seed.dkit")
        store.save(
            file,
            notebook,
            "doc-1",
            notebookCodec,
            mapOf(AssetId.of("cover") to AssetSource.ofBytes(cover), AssetId.of("thumb") to AssetSource.ofBytes(thumbnail)),
        )
        return ZipFile(file).use { zip ->
            zip.entries().toList().map { entry -> entry.name to zip.getInputStream(entry).readBytes() }
        }
    }

    private companion object {
        // Markers replaced after serialising: JSON a serialiser will not emit.
        const val INFINITY = "__SWEEP_INFINITY__"
        const val NEGATIVE_ZERO = "__SWEEP_NEGATIVE_ZERO__"
        const val BEYOND_LONG = "__SWEEP_BEYOND_LONG__"

        /** The values most likely to slip past a check that tests type but not range. */
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

    private fun rebuild(entries: List<Pair<String, ByteArray>>): ByteArray {
        val zip = RawZip()
        for ((name, bytes) in entries) zip.entry(name, bytes)
        return zip.writeTo(File(workspace, "rebuilt.dkit")).readBytes()
    }

    private fun label(kind: String, path: List<Any>, edge: Int?): String =
        "$kind-${path.joinToString("_").ifEmpty { "root" }}-${edge?.let { "edge$it" } ?: "deleted"}"

    private fun assertNoFindings(sweep: String, cases: Int) {
        oracle.writeFindings(File(System.getProperty("documentkit.fuzz.findings") ?: "build/fuzz-findings", sweep))
        assertTrue(
            oracle.findings.isEmpty(),
            "$sweep, $cases cases: ${oracle.findings.size} distinct finding(s)\n" +
                oracle.findings.values.joinToString("\n") { "  - ${it.signature}  [${it.label}]" },
        )
    }

    @Test
    fun everySingleFieldChangeToTheManifest() = runBlocking {
        val entries = seedEntries()
        val manifest = Json.parseToJsonElement(
            entries.first { it.first == DocumentKitFormat.MANIFEST_ENTRY }.second.decodeToString(),
        )
        // The manifest is not what digests protect, so a change to it must never
        // make the container open with different content.
        val expected = ContractOracle.Expected(notebook, mapOf("cover" to cover, "thumb" to thumbnail))

        val allPaths = paths(manifest)
        var cases = 0
        for (path in allPaths) {
            for (edge in listOf<Int?>(null) + EDGES.indices) {
                val changed = bytesOf(edited(manifest, path, edge?.let { EDGES[it] }))
                val input = rebuild(entries.map { (name, bytes) -> if (name == DocumentKitFormat.MANIFEST_ENTRY) name to changed else name to bytes })
                oracle.examine(input, label("manifest", path, edge), expected)
                cases += 1
            }
        }

        assertEquals(allPaths.size * (EDGES.size + 1), cases)
        assertNoFindings("manifest-sweep", cases)
    }

    @Test
    fun everySingleFieldChangeToTheDocumentReDigested() = runBlocking {
        val entries = seedEntries()
        val manifest = Json.parseToJsonElement(
            entries.first { it.first == DocumentKitFormat.MANIFEST_ENTRY }.second.decodeToString(),
        ).jsonObject
        val document = Json.parseToJsonElement(
            entries.first { it.first == DocumentKitFormat.DOCUMENT_ENTRY }.second.decodeToString(),
        )

        val allPaths = paths(document)
        var cases = 0
        for (path in allPaths) {
            for (edge in listOf<Int?>(null) + EDGES.indices) {
                val changed = bytesOf(edited(document, path, edge?.let { EDGES[it] }))
                // Re-digested, so the change survives integrity checking and
                // reaches the codec, migrations and the application's validation.
                val redigested = JsonObject(
                    manifest + mapOf(
                        "document_length" to JsonPrimitive(changed.size.toLong()),
                        "document_sha256" to JsonPrimitive(TestArchive.sha256(changed)),
                    ),
                ).toString().toByteArray()
                val input = rebuild(
                    entries.map { (name, bytes) ->
                        when (name) {
                            DocumentKitFormat.MANIFEST_ENTRY -> name to redigested
                            DocumentKitFormat.DOCUMENT_ENTRY -> name to changed
                            else -> name to bytes
                        }
                    },
                )
                // The document was changed on purpose, so a different result is
                // expected - only the error contract applies.
                oracle.examine(input, label("document", path, edge), expected = null)
                cases += 1
            }
        }

        assertEquals(allPaths.size * (EDGES.size + 1), cases)
        assertNoFindings("document-sweep", cases)
    }
}
