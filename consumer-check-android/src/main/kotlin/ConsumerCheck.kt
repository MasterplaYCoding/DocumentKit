package io.github.masterplaycoding.documentkit.consumercheck

import android.content.Context
import android.net.Uri
import io.github.masterplaycoding.documentkit.AssetId
import io.github.masterplaycoding.documentkit.DocumentCodec
import io.github.masterplaycoding.documentkit.DocumentError
import io.github.masterplaycoding.documentkit.DocumentException
import io.github.masterplaycoding.documentkit.io.AssetSource
import io.github.masterplaycoding.documentkit.io.DocumentStore
import io.github.masterplaycoding.documentkit.io.SaveReceipt
import io.github.masterplaycoding.documentkit.android.DocumentTransfer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable

/**
 * Reachability checks against the resolved artifacts.
 *
 * Nothing here runs on a device. Compiling is the assertion: every symbol below
 * must exist in the published AAR and its transitive dependencies, with the
 * signature the README documents. When documentkit-android published no
 * artifact at all, this file would not have compiled.
 */

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
    validate = { notebook -> if (notebook.title.isBlank()) "a notebook needs a title" else null },
)

/**
 * Imports a document a user picked through the Storage Access Framework.
 *
 * Exercises the whole Android surface: the transfer object, the suspending
 * import, the owned handle, asset access, and the structured error type.
 */
fun importDocument(context: Context, uri: Uri): String = runBlocking {
    val transfer = DocumentTransfer(context)

    try {
        transfer.import(uri, codec).use { opened ->
            val assets = opened.assetIds.joinToString()
            "${opened.document.title} (schema ${opened.manifest.schemaVersion}, assets: $assets)"
        }
    } catch (failure: DocumentException) {
        when (val error = failure.error) {
            is DocumentError.UnsupportedSchema -> "written by a newer version of this app"
            is DocumentError.IntegrityMismatch -> "'${error.entry}' does not match its digest"
            else -> error.detail
        }
    }
}

/** Exports a copy to a destination the user chose. */
fun exportDocument(context: Context, uri: Uri, notebook: Notebook): String = runBlocking {
    val receipt: SaveReceipt.ProviderManagedExport = DocumentTransfer(context).exportCopy(
        uri = uri,
        document = notebook,
        documentId = "consumer-check-android-1",
        codec = codec,
        assets = mapOf(AssetId.of("cover") to AssetSource.ofBytes(ByteArray(16))),
    )
    "exported ${receipt.bytesWritten} bytes to ${receipt.uri}"
}

/** The local-file API must also be reachable from Android, not only from JVM. */
fun localStore(): DocumentStore = DocumentStore()

/** Staging cleanup after a hard process termination. */
fun clearStaging(context: Context): Int = DocumentTransfer(context).cleanStagingDirectory()
