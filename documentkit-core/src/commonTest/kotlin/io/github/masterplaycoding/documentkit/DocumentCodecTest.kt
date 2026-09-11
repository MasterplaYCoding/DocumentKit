package io.github.masterplaycoding.documentkit

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The README promises that structured errors "do not carry document contents".
 * These tests are what makes that a guarantee rather than an intention.
 */
class DocumentCodecTest {

    @Serializable
    private data class Journal(val title: String, val entries: List<String> = emptyList())

    private val codec = DocumentCodec(
        applicationId = "example.journal",
        schemaVersion = 1,
        serializer = Journal.serializer(),
    )

    /** A value no error message may ever reproduce. */
    private val secret = "PATIENT-4417-DIAGNOSIS"

    @Test
    fun aWronglyTypedValueDoesNotAppearInTheError() {
        // title is a String in the model and a number here, so kotlinx fails
        // with a message that quotes the offending value.
        val body = buildJsonObject {
            put("title", JsonPrimitive(42))
            putJsonArray("entries") { add(JsonPrimitive(secret)) }
        }

        val failure = assertIs<DecodeResult.Failure>(codec.decode(body, fromSchemaVersion = 1))
        val error = assertIs<DocumentError.InvalidJson>(failure.error)

        assertFalse(error.reason.contains(secret), "error leaked content: ${error.reason}")
        assertFalse(error.toString().contains(secret))
        assertFalse(DocumentException(error).message.orEmpty().contains(secret))
    }

    @Test
    fun anUnknownFieldDoesNotLeakItsValue() {
        val body = buildJsonObject {
            put("title", "Journal")
            put("classified_note", secret)
        }

        val failure = assertIs<DecodeResult.Failure>(codec.decode(body, fromSchemaVersion = 1))
        val error = assertIs<DocumentError.InvalidJson>(failure.error)

        assertFalse(error.reason.contains(secret), "error leaked content: ${error.reason}")
        // The field *name* is schema, not content, and is what a reader needs.
        assertContains(error.reason, "classified_note")
    }

    @Test
    fun aMissingFieldIsNamedWithoutQuotingTheDocument() {
        val body = buildJsonObject {
            putJsonArray("entries") { add(JsonPrimitive(secret)) }
        }

        val failure = assertIs<DecodeResult.Failure>(codec.decode(body, fromSchemaVersion = 1))
        val error = assertIs<DocumentError.InvalidJson>(failure.error)

        assertFalse(error.reason.contains(secret), "error leaked content: ${error.reason}")
        assertContains(error.reason, "title")
    }

    @Test
    fun theErrorStillSaysSomethingActionable() {
        val body = buildJsonObject { put("title", JsonPrimitive(42)) }

        val failure = assertIs<DecodeResult.Failure>(codec.decode(body, fromSchemaVersion = 1))
        val error = assertIs<DocumentError.InvalidJson>(failure.error)

        // Sanitised must not mean useless: a reader has to be able to act. The
        // field name is named, and the entry is identified.
        assertContains(error.reason, "wrong type")
        assertContains(error.reason, "title")
        assertContains(error.detail, DocumentKitFormat.DOCUMENT_ENTRY)
    }

    @Test
    fun aValidationThatThrowsIsARejectionThatDoesNotQuoteTheDocument() {
        // The application's validate is code written for well-formed documents
        // and handed hostile ones. When it throws, decode reports a rejection
        // - it used to let the exception escape - and keeps only the class,
        // because the message is free to quote the model.
        val strict = DocumentCodec(
            applicationId = "example.journal",
            schemaVersion = 1,
            serializer = Journal.serializer(),
            validate = { journal -> error("cannot validate '${journal.title}'") },
        )
        val body = buildJsonObject { put("title", JsonPrimitive(secret)) }

        val failure = assertIs<DecodeResult.Failure>(strict.decode(body, fromSchemaVersion = 1))

        val error = assertIs<DocumentError.ApplicationValidationFailed>(failure.error)
        assertContains(error.reason, "validate")
        assertContains(error.reason, "IllegalStateException")
        assertFalse(secret in error.toString(), "the error quotes the document: $error")
    }
}
