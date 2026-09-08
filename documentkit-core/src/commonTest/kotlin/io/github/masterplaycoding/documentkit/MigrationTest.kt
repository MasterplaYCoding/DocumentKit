package io.github.masterplaycoding.documentkit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
