package io.github.masterplaycoding.documentkit.io

import io.github.masterplaycoding.documentkit.DocumentException
import io.github.masterplaycoding.documentkit.DocumentKitFormat
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The README promises that structured errors name the entry or migration step
 * involved and "do not carry document contents". These tests exercise that
 * end to end, through a real archive, because the codec-level tests cannot see
 * the paths where DocumentStore builds an error from a parse failure.
 */
class ErrorPrivacyTest {

    private lateinit var workspace: File
    private val store = DocumentStore()

    /** A value that must not appear in any error, message or stack trace. */
    private val secret = "PATIENT-4417-DIAGNOSIS"

    @BeforeTest
    fun setUp() {
        workspace = File.createTempFile("documentkit-privacy-", "").apply {
            delete()
            mkdirs()
        }
    }

    @AfterTest
    fun tearDown() {
        workspace.deleteRecursively()
    }

    private fun file(name: String = "case.dkit") = File(workspace, name)

    private fun assertHidesSecret(failure: DocumentException) {
        val rendered = listOf(
            failure.error.detail,
            failure.error.toString(),
            failure.message.orEmpty(),
            failure.stackTraceToString(),
        )

        for (text in rendered) {
            assertFalse(text.contains(secret), "leaked document content: $text")
        }
    }

    @Test
    fun aMalformedDocumentBodyDoesNotLeakItsContents() = runTest {
        // Truncated JSON. kotlinx reports raw-text parse failures with a slice
        // of the input appended as "JSON input: {…}", which is the worst of the
        // leak paths and the reason parseBody's failure is sanitised.
        val body = """{"title":"Field notes","notes":[{"text":"$secret"”"""
        val target = TestArchive.wellFormed(file(), body = body).build()

        val failure = assertFailsWith<DocumentException> { store.open(target, notebookCodec) }
        assertHidesSecret(failure)
        assertTrue(failure.error.detail.contains(DocumentKitFormat.DOCUMENT_ENTRY))
    }

    @Test
    fun aWronglyTypedFieldDoesNotLeakTheValue() = runTest {
        val body = """{"title":$secret.length,"notes":["$secret"]}"""
        val target = TestArchive.wellFormed(file(), body = body).build()

        val failure = assertFailsWith<DocumentException> { store.open(target, notebookCodec) }
        assertHidesSecret(failure)
    }

    @Test
    fun anUnknownFieldNamesTheFieldButNotItsValue() = runTest {
        val body = """{"title":"Field notes","notes":[],"classified":"$secret"}"""
        val target = TestArchive.wellFormed(file(), body = body).build()

        val failure = assertFailsWith<DocumentException> { store.open(target, notebookCodec) }
        assertHidesSecret(failure)
        // The field name is schema rather than content, and is what a reader
        // needs in order to understand why the document was rejected.
        assertTrue(
            failure.error.detail.contains("classified"),
            "the error should still name the offending field: ${failure.error.detail}",
        )
    }

    @Test
    fun aMalformedManifestDoesNotLeakItsContents() = runTest {
        val target = TestArchive(file())
            .entry(DocumentKitFormat.MANIFEST_ENTRY, """{"document_id":"$secret",""")
            .entry(DocumentKitFormat.DOCUMENT_ENTRY, "{}")
            .build()

        val failure = assertFailsWith<DocumentException> { store.open(target, notebookCodec) }
        assertHidesSecret(failure)
    }
}
