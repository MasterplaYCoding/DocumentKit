package io.github.masterplaycoding.documentkit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class MigrationTest {

    private val v1ToV2 = documentMigration("rename-title", fromVersion = 1) { document ->
        buildJsonObject {
            for ((key, value) in document) {
                if (key != "name") put(key, value)
            }
            put("title", document["name"]?.jsonPrimitive ?: JsonPrimitive("Untitled"))
        }
    }

    private val v2ToV3 = documentMigration("add-tags", fromVersion = 2) { document ->
        buildJsonObject {
            for ((key, value) in document) put(key, value)
            put("tags", JsonPrimitive(""))
        }
    }

    private fun chain(vararg migrations: DocumentMigration, target: Int) =
        MigrationChain(migrations.toList(), target)

    @Test
    fun runsEveryStepInOrder() {
        val result = chain(v1ToV2, v2ToV3, target = 3)
            .migrate(buildJsonObject { put("name", "Notes") }, fromVersion = 1)

        val success = assertIs<MigrationResult.Success>(result)
        assertEquals(listOf("rename-title", "add-tags"), success.applied)
        assertEquals("Notes", success.document["title"]?.jsonPrimitive?.content)
        assertTrue("name" !in success.document)
    }

    @Test
    fun leavesADocumentAtTheTargetVersionAlone() {
        val document = buildJsonObject { put("title", "Notes") }
        val result = chain(v1ToV2, target = 2).migrate(document, fromVersion = 2)

        val success = assertIs<MigrationResult.Success>(result)
        assertEquals(document, success.document)
        assertTrue(success.applied.isEmpty())
    }

    @Test
    fun reportsAGapRatherThanSkippingIt() {
        // Only 2 to 3 is registered, so a version 1 document cannot get there.
        val result = chain(v2ToV3, target = 3)
            .migrate(buildJsonObject { put("name", "Notes") }, fromVersion = 1)

        val failure = assertIs<MigrationResult.Failure>(result)
        assertEquals(DocumentError.MissingMigration(1, 2), failure.error)
    }

    @Test
    fun refusesADocumentFromTheFuture() {
        val result = chain(v1ToV2, target = 2).migrate(JsonObject(emptyMap()), fromVersion = 5)

        val failure = assertIs<MigrationResult.Failure>(result)
        assertEquals(DocumentError.UnsupportedSchema(5, 2), failure.error)
    }

    @Test
    fun reportsTheFailingStepAndReturnsNoPartialDocument() {
        val exploding = documentMigration("explode", fromVersion = 1) {
            error("column 'author' is not a string")
        }

        val result = chain(exploding, target = 2).migrate(JsonObject(emptyMap()), fromVersion = 1)

        val failure = assertIs<MigrationResult.Failure>(result)
        val error = assertIs<DocumentError.MigrationFailed>(failure.error)
        assertEquals("explode", error.step)
        assertEquals(1, error.from)
        assertEquals(2, error.to)
        assertTrue(error.reason.contains("author"))
    }

    // --- where the privacy guarantee stops ---------------------------------

    /**
     * A migration's own exception message reaches the error verbatim, and the
     * document content in it comes along.
     *
     * This is not an oversight and it is not a leak by DocumentKit: the text is
     * the application's, written by its own migration, about its own document,
     * and replacing it with a sanitised placeholder would throw away the only
     * detail that says why the step failed.
     *
     * It is pinned here because the README used to promise, without
     * qualification, that errors "do not carry document contents" - and named
     * migration steps in the same sentence. Someone reading that would
     * reasonably paste a migration failure into a public bug report. The
     * wording now says which half is DocumentKit's to guarantee, and this test
     * is the other half made visible.
     */
    @Test
    fun aMigrationsOwnMessageIsPassedThroughIncludingAnythingInIt() {
        val secret = "patient-4417-diagnosis"
        val exploding = documentMigration("explode", fromVersion = 1) { document ->
            error("cannot migrate: ${document["note"]?.jsonPrimitive?.content}")
        }

        val document = buildJsonObject { put("note", JsonPrimitive(secret)) }
        val result = chain(exploding, target = 2).migrate(document, fromVersion = 1)

        val error = assertIs<DocumentError.MigrationFailed>(
            assertIs<MigrationResult.Failure>(result).error,
        )
        assertTrue(
            error.reason.contains(secret),
            "the application's own message is passed through unchanged",
        )
    }

    @Test
    fun documentKitAddsNoContentOfItsOwnToAMigrationFailure() {
        // The half that *is* guaranteed. When the failure is DocumentKit's to
        // describe rather than the application's, nothing from the document
        // appears - not in the error, not in its rendering.
        val secret = "patient-4417-diagnosis"
        val document = buildJsonObject { put("note", JsonPrimitive(secret)) }

        // A gap in the chain: DocumentKit writes this message itself.
        val gap = chain(v2ToV3, target = 3).migrate(document, fromVersion = 1)
        val missing = assertIs<MigrationResult.Failure>(gap).error
        assertIs<DocumentError.MissingMigration>(missing)
        assertFalse(missing.toString().contains(secret))

        // A document from the future: likewise.
        val future = chain(v1ToV2, target = 2).migrate(document, fromVersion = 9)
        val unsupported = assertIs<MigrationResult.Failure>(future).error
        assertIs<DocumentError.UnsupportedSchema>(unsupported)
        assertFalse(unsupported.toString().contains(secret))
    }

    @Test
    fun aMigrationThatThrowsWithNoMessageStillNamesSomething() {
        // cause.message is null for plenty of exceptions. Falling through to an
        // empty reason would produce an error that says a step failed and
        // nothing else.
        val exploding = documentMigration("silent", fromVersion = 1) {
            throw IllegalStateException()
        }

        val result = chain(exploding, target = 2).migrate(JsonObject(emptyMap()), fromVersion = 1)
        val error = assertIs<DocumentError.MigrationFailed>(
            assertIs<MigrationResult.Failure>(result).error,
        )

        assertEquals("silent", error.step)
        assertTrue(error.reason.isNotBlank(), "an error with no reason is unactionable")
    }

    @Test
    fun rejectsADuplicateStepWhenTheChainIsBuilt() {
        // Registered at construction time, which is when the developer runs
        // anything at all - not on a user's machine opening an old file.
        val other = documentMigration("other-v1", fromVersion = 1) { it }

        val failure = assertFailsWith<IllegalArgumentException> {
            chain(v1ToV2, other, target = 3)
        }
        assertTrue(failure.message!!.contains("two migrations start from schema 1"))
    }

    @Test
    fun rejectsAStepThatSkipsVersions() {
        val leaping = object : DocumentMigration {
            override val name = "leap"
            override val fromVersion = 1
            override val toVersion = 3
            override fun migrate(document: JsonObject) = document
        }

        assertFailsWith<IllegalArgumentException> { chain(leaping, target = 3) }
    }

    @Test
    fun rejectsAStepBeyondTheCodecVersion() {
        assertFailsWith<IllegalArgumentException> { chain(v1ToV2, v2ToV3, target = 2) }
    }

    @Test
    fun knowsHowFarBackItCanRead() {
        assertEquals(1, chain(v1ToV2, v2ToV3, target = 3).oldestSupportedVersion)
        // With only the 2 to 3 step registered, version 1 is unreachable.
        assertEquals(2, chain(v2ToV3, target = 3).oldestSupportedVersion)
    }
}
