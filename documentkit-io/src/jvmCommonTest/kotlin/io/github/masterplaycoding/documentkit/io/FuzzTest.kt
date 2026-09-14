package io.github.masterplaycoding.documentkit.io

import io.github.masterplaycoding.documentkit.AssetId
import io.github.masterplaycoding.documentkit.DocumentException
import io.github.masterplaycoding.documentkit.DocumentKitFormat
import java.io.File
import java.util.zip.ZipFile
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Mutation fuzzing of the reader, against the contract `MalformedInputTest`
 * states: a damaged or hostile container is rejected with a named
 * [DocumentException] - never a crash, a hang, or a silently different
 * document.
 *
 * The hand-written corpus covers the damage someone thought of. This covers
 * what nobody did: thousands of containers, each a valid one mutated a few
 * bytes at a time, checked by `open`, `inspect` and `validate`. Three
 * strategies, because one would never get past the first check:
 *
 * - **raw** - mutate the bytes of a whole archive, deflated or stored. Finds
 *   ZIP-structure problems; mostly stopped early, which is the point.
 * - **manifest** - mutate `manifest.json`, then rebuild a valid ZIP around it.
 *   The manifest is not digest-protected, so this reaches its parsing and
 *   validation directly.
 * - **document** - mutate `document.json` and recompute its length and digest
 *   in the manifest, so the mutation survives integrity checking and reaches
 *   the codec, migrations and validation.
 *
 * Deterministic: the same seed and iteration count produce the same inputs.
 * `jvmTest` runs a small fixed budget on every build; `fuzzSweep` runs a large
 * one with a fresh seed. A finding is written to `build/fuzz-findings` with its
 * stack trace, and once fixed, its input belongs in `fuzz-regressions/`, which
 * [FuzzRegressionTest] replays forever.
 */
class FuzzTest {

    private lateinit var workspace: File
    private val store = DocumentStore()
    private lateinit var oracle: ContractOracle

    @BeforeTest
    fun setUp() {
        workspace = File.createTempFile("documentkit-fuzz-", "").apply {
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

    // --- seeds ---------------------------------------------------------------

    private class Seed(
        val name: String,
        val deflated: ByteArray,
        val entries: List<Pair<String, ByteArray>>,
        /** Null when opening the seed migrates it, so equality is meaningless. */
        val document: Notebook?,
        val assets: Map<String, ByteArray>,
    ) {
        val stored: ByteArray by lazy {
            val zip = RawZip()
            for ((name, bytes) in entries) zip.entry(name, bytes)
            val file = File.createTempFile("documentkit-fuzz-seed-", ".dkit")
            try {
                zip.writeTo(file).readBytes()
            } finally {
                file.delete()
            }
        }
    }

    private fun entriesOf(file: File): List<Pair<String, ByteArray>> = ZipFile(file).use { zip ->
        zip.entries().toList().map { entry -> entry.name to zip.getInputStream(entry).readBytes() }
    }

    private suspend fun seeds(): List<Seed> {
        val cover = ByteArray(300) { (it * 7 % 256).toByte() }
        val thumbnail = "tiny".toByteArray()
        val notebook = Notebook(
            title = "Field notes",
            notes = listOf(Note("first"), Note("with a picture", imageAssetId = "cover")),
        )
        val withAssets = File(workspace, "seed-assets.dkit")
        store.save(
            withAssets,
            notebook,
            "doc-1",
            notebookCodec,
            mapOf(AssetId.of("cover") to AssetSource.ofBytes(cover), AssetId.of("thumb") to AssetSource.ofBytes(thumbnail)),
        )

        val minimal = File(workspace, "seed-minimal.dkit")
        store.save(minimal, Notebook("A"), "doc-2", notebookCodec)

        val legacy = TestArchive.wellFormed(
            File(workspace, "seed-legacy.dkit"),
            body = legacyNotebookBody("Old", listOf("one", "two")).toString(),
            schemaVersion = 1,
        ).build()

        return listOf(
            Seed("assets", withAssets.readBytes(), entriesOf(withAssets), notebook, mapOf("cover" to cover, "thumb" to thumbnail)),
            Seed("minimal", minimal.readBytes(), entriesOf(minimal), Notebook("A"), emptyMap()),
            Seed("legacy", legacy.readBytes(), entriesOf(legacy), document = null, assets = emptyMap()),
        )
    }

    // --- mutation --------------------------------------------------------------

    private val interestingBytes = intArrayOf(0x00, 0x01, 0x7F, 0x80, 0xFF, '"'.code, '\\'.code, '{'.code, '['.code)
    private val interestingInts = intArrayOf(0, 1, -1, 0x7FFFFFFF, Int.MIN_VALUE, 0xFFFF, 0x10000, 255, 256)

    /** Text worth splicing into JSON: structure, extremes, and escapes. */
    private val tokens = listOf(
        "{", "}", "[", "]", "\"", ",", ":", "null", "true", "-1", "0", "1e999", "-0",
        "9223372036854775808", "99999999999999999999999", "1.5", "\"\"", "\\u0000", "\\ud800",
        "[".repeat(600), "{\"a\":".repeat(300), "\"assets\":[]", "\"schema_version\":", "\"length\":",
        "../", "..\\", "/", "\u0000", "é", "😀", "PK",
    )

    private fun mutate(input: ByteArray, random: Random, textual: Boolean): ByteArray {
        var bytes = input.copyOf()
        repeat(1 + random.nextInt(if (textual) 3 else 4)) {
            if (bytes.isEmpty()) bytes = byteArrayOf(0)
            val at = random.nextInt(bytes.size)
            bytes = when (random.nextInt(if (textual) 8 else 7)) {
                0 -> bytes.also { it[at] = (it[at].toInt() xor (1 shl random.nextInt(8))).toByte() }
                1 -> bytes.also { it[at] = interestingBytes.random(random).toByte() }
                2 -> {
                    // A little-endian int, as ZIP stores lengths and offsets.
                    val value = interestingInts.random(random)
                    bytes.also { target ->
                        for (i in 0 until 4) if (at + i < target.size) target[at + i] = (value ushr (8 * i)).toByte()
                    }
                }
                3 -> bytes.copyOf(at) // truncate
                4 -> bytes.copyOfRange(0, at) + bytes.copyOfRange(minOf(bytes.size, at + 1 + random.nextInt(16)), bytes.size)
                5 -> {
                    val length = minOf(bytes.size - at, 1 + random.nextInt(32))
                    bytes.copyOfRange(0, at + length) + bytes.copyOfRange(at, at + length) + bytes.copyOfRange(at + length, bytes.size)
                }
                6 -> bytes.copyOfRange(0, at) + ByteArray(1 + random.nextInt(8)) { random.nextInt(256).toByte() } + bytes.copyOfRange(at, bytes.size)
                else -> bytes.copyOfRange(0, at) + tokens.random(random).toByteArray() + bytes.copyOfRange(at, bytes.size)
            }
        }
        return bytes
    }

    private fun rebuild(entries: List<Pair<String, ByteArray>>): ByteArray {
        val zip = RawZip()
        for ((name, bytes) in entries) zip.entry(name, bytes)
        val file = File(workspace, "rebuilt.dkit")
        return zip.writeTo(file).readBytes()
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** Mutates document.json and makes the manifest agree with the result. */
    private fun consistentDocumentMutation(seed: Seed, random: Random): ByteArray? {
        val manifestBytes = seed.entries.first { it.first == DocumentKitFormat.MANIFEST_ENTRY }.second
        val document = seed.entries.first { it.first == DocumentKitFormat.DOCUMENT_ENTRY }.second
        val mutated = mutate(document, random, textual = true)
        val manifest = runCatching { json.parseToJsonElement(manifestBytes.decodeToString()).jsonObject }
            .getOrNull() ?: return null
        val updated = JsonObject(
            manifest + mapOf(
                "document_length" to JsonPrimitive(mutated.size.toLong()),
                "document_sha256" to JsonPrimitive(TestArchive.sha256(mutated)),
            ),
        )
        return rebuild(
            seed.entries.map { (name, bytes) ->
                when (name) {
                    DocumentKitFormat.MANIFEST_ENTRY -> name to updated.toString().toByteArray()
                    DocumentKitFormat.DOCUMENT_ENTRY -> name to mutated
                    else -> name to bytes
                }
            },
        )
    }

    // --- the oracle --------------------------------------------------------------

    /** Shared with [FieldSweepTest] through [ContractOracle], so both judge findings alike. */
    private fun examine(input: ByteArray, label: String, seed: Seed, equalityApplies: Boolean) {
        val expected = seed.document?.takeIf { equalityApplies }?.let { ContractOracle.Expected(it, seed.assets) }
        oracle.examine(input, label, expected)
    }

    @Test
    fun mutatedContainersAreRejectedWithNamedErrorsOnly() = runBlocking {
        val iterations = System.getProperty("documentkit.fuzz.iterations")?.toInt() ?: 1_500
        val seedValue = when (val requested = System.getProperty("documentkit.fuzz.seed")) {
            null -> 20_260_911L
            "random" -> System.nanoTime()
            else -> requested.toLong()
        }
        println("FuzzTest: seed $seedValue, $iterations iterations")
        val random = Random(seedValue)
        val seeds = seeds()

        for (iteration in 0 until iterations) {
            val seed = seeds.random(random)
            when (random.nextInt(4)) {
                0 -> examine(mutate(seed.deflated, random, textual = false), "raw-deflated", seed, equalityApplies = true)
                1 -> examine(mutate(seed.stored, random, textual = false), "raw-stored", seed, equalityApplies = true)
                2 -> {
                    val mutated = seed.entries.map { (name, bytes) ->
                        if (name == DocumentKitFormat.MANIFEST_ENTRY) name to mutate(bytes, random, textual = true) else name to bytes
                    }
                    examine(rebuild(mutated), "manifest", seed, equalityApplies = true)
                }
                else -> consistentDocumentMutation(seed, random)?.let {
                    // The document was changed on purpose and re-digested; a
                    // different result is expected, not a finding.
                    examine(it, "document", seed, equalityApplies = false)
                }
            }
        }

        oracle.writeFindings(File(System.getProperty("documentkit.fuzz.findings") ?: "build/fuzz-findings"))

        assertTrue(
            oracle.findings.isEmpty(),
            "seed $seedValue, $iterations iterations: ${oracle.findings.size} distinct finding(s)\n" +
                oracle.findings.keys.joinToString("\n") { "  - $it" },
        )
    }
}
