package io.github.masterplaycoding.documentkit.io

import io.github.masterplaycoding.documentkit.DocumentCodec
import io.github.masterplaycoding.documentkit.DocumentError
import io.github.masterplaycoding.documentkit.DocumentException
import io.github.masterplaycoding.documentkit.documentMigration
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

/**
 * Opening an old document, end to end.
 *
 * The version-1 fixture is generated from a literal body rather than by an old
 * build of the library, which is the honest way to keep a golden fixture for a
 * schema that no longer has code to write it.
 */
class MigrationThroughStoreTest {

    private lateinit var workspace: File
    private val store = DocumentStore()

    @BeforeTest
    fun setUp() {
        workspace = File.createTempFile("documentkit-migrate-", "").apply {
            delete()
            mkdirs()
        }
    }

    @AfterTest
    fun tearDown() {
        workspace.deleteRecursively()
    }

    /** A schema-version-1 notebook: notes were bare strings back then. */
    private fun legacyFixture(name: String = "legacy.dkit"): File =
        TestArchive.wellFormed(
            file = File(workspace, name),
            body = """{"title":"Old notebook","notes":["first","second"]}""",
            schemaVersion = 1,
        ).build()

    @Test
    fun migratesAnOldDocumentOnOpen() = runTest {
        store.open(legacyFixture(), notebookCodec).use { opened ->
            assertEquals("Old notebook", opened.document.title)
            assertEquals(listOf("first", "second"), opened.document.notes.map { it.text })
            assertEquals(listOf("notes-become-objects"), opened.migrationsApplied)
            // The manifest still records the version the *file* was written at.
            assertEquals(1, opened.manifest.schemaVersion)
        }
    }

    @Test
    fun doesNotRewriteTheSourceFileAsASideEffectOfOpeningIt() = runTest {
        val file = legacyFixture()
        val before = file.readBytes()

        store.open(file, notebookCodec).use { it.document }

        // Upgrading a user's file because they looked at it is not a decision
        // a library gets to make. Saving is what writes.
        assertContentEquals(before, file.readBytes())
    }

    @Test
    fun savingAMigratedModelProducesACurrentSchemaDocument() = runTest {
        val legacy = legacyFixture()
        val upgraded = File(workspace, "upgraded.dkit")

        val model = store.open(legacy, notebookCodec).use { it.document }
        store.save(upgraded, model, "fixture-1", notebookCodec)

        store.open(upgraded, notebookCodec).use { opened ->
            assertEquals(2, opened.manifest.schemaVersion)
            assertEquals(emptyList(), opened.migrationsApplied)
        }
        // And the original is still exactly as it was.
        assertEquals(1, store.open(legacy, notebookCodec).use { it.manifest.schemaVersion })
    }

    @Test
    fun reportsAMissingMigrationStepAndLeavesTheSourceAlone() = runTest {
        val file = legacyFixture()
        val before = file.readBytes()

        // A codec at version 2 with no registered step from 1.
        val gapped = DocumentCodec(
            applicationId = "example.notebook",
            schemaVersion = 2,
            serializer = Notebook.serializer(),
        )

        val failure = assertFailsWith<DocumentException> { store.open(file, gapped) }
        assertEquals(DocumentError.MissingMigration(1, 2), failure.error)
        assertContentEquals(before, file.readBytes())
    }

    @Test
    fun reportsTheFailingMigrationStepByName() = runTest {
        val file = legacyFixture()
        val before = file.readBytes()

        val exploding = DocumentCodec(
            applicationId = "example.notebook",
            schemaVersion = 2,
            serializer = Notebook.serializer(),
            migrations = listOf(
                documentMigration("broken-step", fromVersion = 1) {
                    error("could not read the notes array")
                },
            ),
        )

        val failure = assertFailsWith<DocumentException> { store.open(file, exploding) }
        val error = assertIs<DocumentError.MigrationFailed>(failure.error)
        assertEquals("broken-step", error.step)
        assertContentEquals(before, file.readBytes())
    }
}
