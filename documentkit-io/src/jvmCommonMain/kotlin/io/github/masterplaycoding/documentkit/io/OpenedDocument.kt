package io.github.masterplaycoding.documentkit.io

import io.github.masterplaycoding.documentkit.AssetId
import io.github.masterplaycoding.documentkit.DocumentError
import io.github.masterplaycoding.documentkit.DocumentException
import io.github.masterplaycoding.documentkit.DocumentKitFormat
import io.github.masterplaycoding.documentkit.Manifest
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * An open document: the decoded model, its manifest, and access to its assets.
 *
 * The handle **owns** resources - the open archive, and any staging file
 * created to read from a content provider - until [close] is called. Use it
 * with `use { }`.
 *
 * Assets are not extracted anywhere on open. They are streamed from the
 * already-verified archive when asked for, into a destination the caller
 * chooses. Nothing lands in a shared temporary directory, and nothing is
 * registered in a global cache, so closing the handle really does release
 * everything it took.
 *
 * One handle is not safe for concurrent use: a ZIP handle has a position, and
 * two threads reading assets through the same handle will interleave. Separate
 * handles on the same file are fine.
 *
 * The application must not modify the underlying file while a handle is open.
 */
public class OpenedDocument<T : Any> internal constructor(
    /** The decoded, migrated, validated application model. */
    public val document: T,
    public val manifest: Manifest,
    /** Names the migrations that ran while opening, in order. Empty if none. */
    public val migrationsApplied: List<String>,
    private val archive: ZipFile,
    /** A staging copy this handle owns and must delete, if there is one. */
    private val ownedStagingFile: File?,
) : Closeable {

    private var closed = false

    /** Ids of every asset the container holds, including unreferenced ones. */
    public val assetIds: Set<AssetId>
        get() = manifest.assets.map { it.id }.toSet()

    /**
     * Opens a stream over one asset's bytes.
     *
     * The caller closes the stream. The bytes were verified against the
     * manifest's digest when the document was opened, so this does not
     * re-verify them.
     */
    public fun openAsset(id: AssetId): InputStream {
        check(!closed) { "document handle is closed" }
        val entry = archive.getEntry(DocumentKitFormat.assetPath(id))
            ?: throw DocumentException(
                DocumentError.MissingEntry(DocumentKitFormat.assetPath(id)),
            )
        return archive.openEntry(entry)
    }

    /**
     * Streams one asset into [destination], creating or replacing it.
     *
     * This is the supported way to get an asset out of a document. There is
     * deliberately no "extract everything to a temporary folder" operation:
     * that produces files nobody owns, which outlive the handle and are
     * cleaned up by nothing.
     */
    public fun copyAssetTo(id: AssetId, destination: File) {
        destination.parentFile?.mkdirs()
        openAsset(id).use { input ->
            destination.outputStream().buffered().use { output -> input.copyTo(output) }
        }
    }

    /** Reads one asset entirely into memory. Only for assets you know are small. */
    public fun readAsset(id: AssetId): ByteArray = openAsset(id).use { it.readBytes() }

    /**
     * Releases the archive and deletes any staging file this handle owns.
     *
     * A staging file that cannot be deleted is reported through
     * [cleanupWarning] rather than thrown: failing to close a document is not
     * a reason for an application to lose the document.
     */
    override fun close() {
        if (closed) return
        closed = true

        try {
            archive.close()
        } finally {
            val staging = ownedStagingFile
            if (staging != null && staging.exists() && !staging.delete()) {
                cleanupWarning = "could not delete staging file ${staging.absolutePath}"
            }
        }
    }

    /**
     * Set when [close] could not remove a file it owned. Null otherwise.
     *
     * Surfaced rather than hidden, because leaked staging files are a real
     * disk-usage problem, and silently swallowing the reason makes them
     * impossible to diagnose.
     */
    public var cleanupWarning: String? = null
        private set
}
