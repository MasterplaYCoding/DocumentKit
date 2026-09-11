package io.github.masterplaycoding.documentkit.android

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * A content provider with the failure modes real ones have.
 *
 * Registered with Robolectric, it is reached through Android's own
 * `ContentResolver` code - `openInputStream` and `openOutputStream` go through
 * `openAssetFileDescriptor` to [openFile] exactly as they would on a device -
 * so these tests exercise the framework path rather than a stub standing in
 * for it. The first path segment picks the behaviour:
 *
 * - `docs/<name>` - a file under [root], opened in the mode asked for.
 * - `null/...` - returns no descriptor, which a provider may do.
 * - `denied/...` - throws `SecurityException`, what a revoked grant produces.
 * - `missing/...` - throws `FileNotFoundException`, a deleted document.
 */
class TestDocumentProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? =
        when (uri.pathSegments.firstOrNull()) {
            "docs" -> ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.parseMode(mode))
            "null" -> null
            "denied" -> throw SecurityException("Permission Denial: no grant for $uri")
            "missing" -> throw FileNotFoundException("no document at $uri")
            else -> throw IllegalArgumentException("unexpected test uri $uri")
        }

    override fun getType(uri: Uri): String = "application/octet-stream"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        const val AUTHORITY = "io.github.masterplaycoding.documentkit.test.documents"

        /** Where `docs/` URIs live. Set by each test. */
        lateinit var root: File

        fun uri(path: String): Uri = Uri.parse("content://$AUTHORITY/$path")

        fun fileFor(uri: Uri): File = File(root, uri.lastPathSegment!!)
    }
}
