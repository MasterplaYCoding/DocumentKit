package readme

/**
 * The README's Kotlin snippets, compiled.
 *
 * Every snippet in README.md between "Saving a document" and "Opening a
 * damaged one" appears here in the form it is printed, including its imports.
 * If a snippet stops compiling - a renamed parameter, a changed signature, a
 * forgotten `runBlocking` - this build fails.
 *
 * The README used to contain code that could not compile at all: `save` and
 * `open` are suspend functions and the snippets were written as top-level
 * statements, `Note` and half a dozen other identifiers were never defined,
 * and no imports were shown for an API that spans two packages. All of that
 * was invisible because nothing ever compiled it.
 */

import io.github.masterplaycoding.documentkit.AssetId
import io.github.masterplaycoding.documentkit.DocumentCodec
import io.github.masterplaycoding.documentkit.DocumentError
import io.github.masterplaycoding.documentkit.DocumentException
import io.github.masterplaycoding.documentkit.documentMigration
import io.github.masterplaycoding.documentkit.io.AssetSource
import io.github.masterplaycoding.documentkit.io.DocumentStore
import io.github.masterplaycoding.documentkit.io.SaveReceipt
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

// --- Saving a document -----------------------------------------------------

@Serializable
data class Notebook(val title: String, val notes: List<Note> = emptyList())

@Serializable
data class Note(val text: String, val imageAssetId: String? = null)

val codec: DocumentCodec<Notebook> = DocumentCodec(
    applicationId = "example.notebook",
    schemaVersion = 1,
    serializer = Notebook.serializer(),
    referencedAssets = { notebook ->
        notebook.notes.mapNotNull { note -> note.imageAssetId?.let(AssetId::of) }.toSet()
    },
    validate = { notebook ->
        if (notebook.title.isBlank()) "a notebook needs a title" else null
    },
)

fun saveExample(directory: File) = runBlocking {
    val store = DocumentStore()
    val coverImage = File(directory, "cover.png")

    val notebook = Notebook(
        title = "Field notes",
        notes = listOf(Note("first"), Note("with a picture", imageAssetId = "cover")),
    )

    val receipt = store.save(
        destination = File(directory, "field-notes.dkit"),
        document = notebook,
        documentId = "9c1f-4a2e-…",
        codec = codec,
        assets = mapOf(AssetId.of("cover") to AssetSource.ofFile(coverImage)),
    )

    check(receipt is SaveReceipt.AtomicReplace)
}

// --- Opening one -----------------------------------------------------------

fun openExample(store: DocumentStore, directory: File) = runBlocking {
    val documentFile = File(directory, "field-notes.dkit")

    store.open(documentFile, codec).use { opened ->
        println(opened.document.title)
        println(opened.migrationsApplied)

        opened.copyAssetTo(AssetId.of("cover"), File(directory, "extracted-cover.png"))
    }
}

// --- Opening an older one --------------------------------------------------

val notesBecomeObjects = documentMigration("notes-become-objects", fromVersion = 1) { document ->
    buildJsonObject {
        put("title", document["title"] ?: JsonPrimitive("Untitled"))
        putJsonArray("notes") {
            document["notes"]?.jsonArray?.forEach { note ->
                add(buildJsonObject { put("text", note.jsonPrimitive.content) })
            }
        }
    }
}

val migratingCodec: DocumentCodec<Notebook> = DocumentCodec(
    applicationId = "example.notebook",
    schemaVersion = 2,
    serializer = Notebook.serializer(),
    migrations = listOf(notesBecomeObjects),
)

// --- Opening a damaged one -------------------------------------------------

fun damagedExample(store: DocumentStore, suspiciousFile: File): String = runBlocking {
    try {
        store.open(suspiciousFile, codec).use { opened -> opened.document.title }
    } catch (failure: DocumentException) {
        when (val error = failure.error) {
            is DocumentError.IntegrityMismatch -> "'${error.entry}' does not match its digest"
            is DocumentError.UnsupportedSchema -> "written by a newer version of this app"
            is DocumentError.MissingMigration -> "too old for this build to read"
            else -> error.detail
        }
    }
}
