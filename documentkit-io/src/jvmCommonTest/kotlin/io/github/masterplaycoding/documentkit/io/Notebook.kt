package io.github.masterplaycoding.documentkit.io

import io.github.masterplaycoding.documentkit.AssetId
import io.github.masterplaycoding.documentkit.DocumentCodec
import io.github.masterplaycoding.documentkit.documentMigration
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * A small application model used across the IO tests.
 *
 * It exists to prove the API is usable from a plain serialisable model with no
 * presentation types, no framework and no library-specific base class.
 */
@Serializable
data class Notebook(
    val title: String,
    val notes: List<Note> = emptyList(),
)

@Serializable
data class Note(
    val text: String,
    val imageAssetId: String? = null,
)

/** Schema version 2 codec, with a migration from version 1. */
val notebookCodec: DocumentCodec<Notebook> = DocumentCodec(
    applicationId = "example.notebook",
    schemaVersion = 2,
    serializer = Notebook.serializer(),
    migrations = listOf(
        // Version 1 stored notes as bare strings; version 2 stores objects so
        // a note can carry an image.
        documentMigration("notes-become-objects", fromVersion = 1) { document ->
            buildJsonObject {
                put("title", document["title"] ?: JsonPrimitive("Untitled"))
                putJsonArray("notes") {
                    document["notes"]?.jsonArray?.forEach { note ->
                        add(buildJsonObject { put("text", note.jsonPrimitive.content) })
                    }
                }
            }
        },
    ),
    referencedAssets = { notebook ->
        notebook.notes.mapNotNull { note -> note.imageAssetId?.let(AssetId::of) }.toSet()
    },
    validate = { notebook ->
        when {
            notebook.title.isBlank() -> "a notebook needs a title"
            else -> null
        }
    },
)

/** A second, unrelated model, to prove the API is not shaped around notebooks. */
@Serializable
data class Diagram(
    val nodes: List<String> = emptyList(),
    val edges: List<String> = emptyList(),
    val backgroundAssetId: String? = null,
)

val diagramCodec: DocumentCodec<Diagram> = DocumentCodec(
    applicationId = "example.diagram",
    schemaVersion = 1,
    serializer = Diagram.serializer(),
    referencedAssets = { diagram ->
        setOfNotNull(diagram.backgroundAssetId?.let(AssetId::of))
    },
)

/** Builds the JSON body a version-1 notebook would have had. */
fun legacyNotebookBody(title: String, notes: List<String>) = buildJsonObject {
    put("title", title)
    putJsonArray("notes") { notes.forEach { add(JsonPrimitive(it)) } }
}.let { it.jsonObject }
