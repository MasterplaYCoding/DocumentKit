package io.github.masterplaycoding.documentkit.io

import io.github.masterplaycoding.documentkit.AssetId
import io.github.masterplaycoding.documentkit.DocumentError
import io.github.masterplaycoding.documentkit.DocumentException
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.test.runTest

/**
 * Every input [FuzzTest] has ever found a bug with, replayed on every build.
 *
 * A fuzzer run is a search; its seed moves on and the input that found a bug
 * is not generated again. Committing the input is what turns a finding into a
 * regression test. Each file in `src/jvmCommonTest/fuzz-regressions/` must be
 * handled the contract's way by `open`, `inspect` and `validate` - success or
 * a [DocumentException], nothing else - and the ones below also pin the exact
 * error, so a fix cannot drift into a different, vaguer one.
 */
class FuzzRegressionTest {

    private val store = DocumentStore()

    private val directory: File by lazy {
        var candidate: File? = File(".").absoluteFile
        while (candidate != null) {
            val found = File(candidate, "documentkit-io/src/jvmCommonTest/fuzz-regressions")
                .takeIf { it.isDirectory }
                ?: File(candidate, "src/jvmCommonTest/fuzz-regressions").takeIf { it.isDirectory }
            if (found != null) return@lazy found
            candidate = candidate.parentFile
        }
        throw AssertionError("could not find fuzz-regressions from ${File(".").absolutePath}")
    }

    private fun regression(name: String) = File(directory, name).also {
        check(it.isFile) { "missing regression input $name" }
    }

    @Test
    fun everyRecordedInputIsHandledTheContractsWay() = runTest {
        val inputs = directory.listFiles { file -> file.extension == "dkit" }.orEmpty().sortedBy { it.name }
        // An empty directory would pass the loop below without testing anything.
        assertTrue(inputs.size >= 4, "expected the recorded inputs, found ${inputs.map { it.name }}")

        for (input in inputs) {
            for ((operation, run) in listOf<Pair<String, suspend () -> Unit>>(
                "open" to { store.open(input, notebookCodec).close() },
                "inspect" to { store.inspect(input) },
                "validate" to { store.validate(input) },
            )) {
                try {
                    run()
                } catch (expected: DocumentException) {
                    // The contract.
                } catch (other: Throwable) {
                    fail("$operation(${input.name}) threw ${other::class.java.name}: ${other.message}")
                }
            }
        }
    }

    @Test
    fun aLocalHeaderWithABadSignatureIsAnInvalidEntry() = runTest {
        // The central directory lists the entry correctly; its local header,
        // which java.util.zip reads only when the bytes are, does not start
        // with PK\3\4. Used to escape as java.util.zip.ZipException.
        val failure = assertFailsWith<DocumentException> {
            store.open(regression("local-header-bad-signature.dkit"), notebookCodec)
        }

        val error = assertIs<DocumentError.InvalidEntry>(failure.error)
        assertContains(error.reason, "LOC header")
    }

    @Test
    fun aCodecThatThrowsOnAHostileDocumentIsARejectionThatDoesNotQuoteIt() = runTest {
        // document.json, re-digested so it passes integrity, gives a note the
        // image id "c]over". The test codec builds ids with AssetId.of - as the
        // README's example does - which throws IllegalArgumentException. That
        // used to escape open() as-is, with the id in its message.
        val failure = assertFailsWith<DocumentException> {
            store.open(regression("referenced-asset-id-malformed.dkit"), notebookCodec)
        }

        val error = assertIs<DocumentError.ApplicationValidationFailed>(failure.error)
        assertContains(error.reason, "referencedAssets")
        assertContains(error.reason, "IllegalArgumentException")
        // Document content stays out of errors DocumentKit writes.
        assertTrue("c]over" !in failure.toString(), "the error quotes the document: $failure")
    }

    @Test
    fun aHugeDeclaredSizeDoesNotSizeAnAllocation() = runTest {
        // assets/cover's central directory entry claims 2,147,483,948 bytes;
        // there are 300, and they match their digest, so the document opens -
        // correctly: the reader never trusts declared sizes. readAsset then
        // sized its buffer from available(), which java.util.zip answers with
        // the declared size, and died with "Requested array size exceeds VM
        // limit".
        store.open(regression("declared-size-huge.dkit"), notebookCodec).use { opened ->
            val cover = AssetId.of("cover")
            val expected = opened.manifest.assets.single { it.id == cover }.length

            assertEquals(expected, opened.readAsset(cover).size.toLong())
            // The same trap for a caller who reads the stream themselves.
            assertEquals(expected, opened.openAsset(cover).use { it.readBytes() }.size.toLong())
        }
    }

    @Test
    fun aLocalHeaderPointingPastTheEndIsAnInvalidEntry() = runTest {
        // Used to escape as a bare java.io.EOFException, with no message.
        val failure = assertFailsWith<DocumentException> {
            store.open(regression("local-header-past-end.dkit"), notebookCodec)
        }

        val error = assertIs<DocumentError.InvalidEntry>(failure.error)
        assertContains(error.reason, "ends inside")
    }
}
