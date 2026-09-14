package io.github.masterplaycoding.documentkit.io

import io.github.masterplaycoding.documentkit.DocumentException
import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.runBlocking

/**
 * The reader's contract, checked against one input.
 *
 * `MalformedInputTest` states it: a damaged or hostile container is rejected
 * with a named [DocumentException] - never another exception, never a hang,
 * and never a container that opens with content its digests do not describe.
 * [FuzzTest] searches for inputs that break it at random; [FieldSweepTest]
 * tries every single-field change deterministically. Both hand each input to
 * this class, so the two can never disagree about what counts as a finding.
 */
internal class ContractOracle(
    private val store: DocumentStore,
    private val workspace: File,
) : AutoCloseable {

    /** What a mutated container must still decode to, when it opens without migrating. */
    class Expected(val document: Notebook, val assets: Map<String, ByteArray>)

    class Finding(val signature: String, val detail: String, val input: ByteArray, val label: String)

    /** Distinct problems found so far, keyed by a signature that ignores the input. */
    val findings = LinkedHashMap<String, Finding>()

    private var executor: ExecutorService = Executors.newSingleThreadExecutor()

    /**
     * Runs `open`, `inspect` and `validate` on [input]. When [expected] is given
     * and the container opens without a migration, its document and every asset
     * must equal it: digests cover all of them, so anything else has slipped past
     * integrity checking.
     */
    fun examine(input: ByteArray, label: String, expected: Expected?) {
        val file = File(workspace, "case.dkit").apply { writeBytes(input) }

        val problems = listOfNotNull(
            attempt("open") {
                store.open(file, notebookCodec).use { opened ->
                    if (expected != null && opened.migrationsApplied.isEmpty()) {
                        check(opened.document == expected.document) {
                            "opened a different document: ${opened.document}"
                        }
                        for (asset in opened.manifest.assets) {
                            val original = expected.assets[asset.id.value]
                                ?: error("opened an asset the original did not have: ${asset.id.value}")
                            check(opened.readAsset(asset.id).contentEquals(original)) {
                                "asset ${asset.id.value} opened with different bytes"
                            }
                        }
                    }
                }
            }?.let { (signature, detail) ->
                // check() and error() surface as IllegalStateException; label
                // them as what they are rather than as a crash.
                signature.replace("threw java.lang.IllegalStateException", "integrity oracle failed") to detail
            },
            attempt("inspect") { store.inspect(file) },
            attempt("validate") { store.validate(file) },
        )

        for ((signature, detail) in problems) {
            findings.getOrPut(signature) { Finding(signature, detail, input, label) }
        }
    }

    /** Writes each finding's input and stack trace under [directory], if there are any. */
    fun writeFindings(directory: File) {
        if (findings.isEmpty()) return
        directory.mkdirs()
        findings.values.forEachIndexed { index, finding ->
            File(directory, "finding-$index-${finding.label}.dkit").writeBytes(finding.input)
            File(directory, "finding-$index-${finding.label}.txt").writeText("${finding.signature}\n\n${finding.detail}")
        }
    }

    /**
     * Runs [block] with a deadline. Returns a problem description, or null when
     * the operation either succeeded or failed the way the contract allows.
     */
    private fun attempt(operation: String, block: suspend () -> Unit): Pair<String, String>? {
        val future = executor.submit<Unit> { runBlocking { block() } }
        return try {
            future.get(20, TimeUnit.SECONDS)
            null
        } catch (timeout: TimeoutException) {
            // A blocked reader cannot be interrupted from here; abandon it.
            future.cancel(true)
            executor.shutdownNow()
            executor = Executors.newSingleThreadExecutor()
            "$operation hung" to "no result within 20 s"
        } catch (failure: ExecutionException) {
            val cause = failure.cause ?: failure
            if (cause is DocumentException) {
                null
            } else {
                val frame = cause.stackTrace.firstOrNull { it.className.contains("documentkit") }
                    ?: cause.stackTrace.firstOrNull()
                "$operation threw ${cause::class.java.name} at $frame" to cause.stackTraceToString()
            }
        }
    }

    override fun close() {
        executor.shutdownNow()
    }
}
