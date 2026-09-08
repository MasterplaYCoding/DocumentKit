package io.github.masterplaycoding.documentkit.io

import io.github.masterplaycoding.documentkit.AssetId
import io.github.masterplaycoding.documentkit.DocumentError
import io.github.masterplaycoding.documentkit.DocumentKitFormat
import io.github.masterplaycoding.documentkit.DocumentLimits
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Structural checks over an archive's entry list, before a single byte of
 * content is read.
 *
 * Everything here is cheap and answers "is this even shaped like one of our
 * documents". Content verification - actual lengths, digests - happens
 * afterwards, and only for an archive that passed this.
 */
internal object ArchiveInventory {

    /**
     * Validates every entry name and returns the asset entries by id.
     *
     * The rules, and why each exists:
     *
     * - **Entry count** is capped first, so a pathological archive costs a
     *   count rather than a walk.
     * - **Directory entries are rejected.** The format is a fixed set of file
     *   paths; a directory entry adds nothing and is an easy place to hide
     *   something odd.
     * - **Backslashes, absolute paths, `..` segments and empty segments are
     *   rejected.** These are the classic archive-extraction escapes. They
     *   cannot hurt this reader, which never joins an entry name to a
     *   filesystem path - but a container carrying them is malformed, and
     *   saying so is better than silently tolerating it.
     * - **Unknown entries are rejected**, rather than ignored. A file with
     *   contents this build does not understand should be reported, not
     *   quietly dropped on the next save.
     * - **Duplicate names are rejected.** A ZIP can declare the same name
     *   twice; which one a reader picks is a fine way to disagree with the
     *   application that wrote it.
     */
    fun validate(archive: ZipFile, limits: DocumentLimits): InventoryResult {
        val errors = mutableListOf<DocumentError>()
        val assets = LinkedHashMap<AssetId, ZipEntry>()
        val seen = mutableSetOf<String>()
        var count = 0

        val entries = archive.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()

            count += 1
            if (count > limits.maxEntryCount) {
                errors += DocumentError.LimitExceeded("entry count", limits.maxEntryCount.toLong())
                break
            }

            val name = entry.name

            if (!seen.add(name)) {
                errors += DocumentError.DuplicateEntry(name)
                continue
            }

            if (entry.isDirectory) {
                errors += DocumentError.InvalidEntry(name, "the format contains files only")
                continue
            }

            val pathProblem = invalidPathReason(name)
            if (pathProblem != null) {
                errors += DocumentError.InvalidEntry(name, pathProblem)
                continue
            }

            if (!DocumentKitFormat.isKnownEntry(name)) {
                errors += DocumentError.InvalidEntry(name, "not part of the DocumentKit format")
                continue
            }

            if (name.startsWith(DocumentKitFormat.ASSET_PREFIX)) {
                val raw = name.removePrefix(DocumentKitFormat.ASSET_PREFIX)
                val id = AssetId.parseOrNull(raw)
                if (id == null) {
                    errors += DocumentError.InvalidEntry(name, "'$raw' is not a valid asset id")
                    continue
                }
                assets[id] = entry
            }
        }

        if (!seen.contains(DocumentKitFormat.MANIFEST_ENTRY)) {
            errors += DocumentError.MissingEntry(DocumentKitFormat.MANIFEST_ENTRY)
        }
        if (!seen.contains(DocumentKitFormat.DOCUMENT_ENTRY)) {
            errors += DocumentError.MissingEntry(DocumentKitFormat.DOCUMENT_ENTRY)
        }

        return InventoryResult(assets = assets, errors = errors)
    }

    private fun invalidPathReason(name: String): String? = when {
        name.isEmpty() -> "empty entry name"
        name.contains('\\') -> "backslash is not a path separator in this format"
        name.startsWith('/') -> "absolute paths are not allowed"
        name.contains(':') -> "drive-qualified paths are not allowed"
        name.split('/').any { it.isEmpty() || it == "." || it == ".." } ->
            "path traversal segments are not allowed"
        else -> null
    }
}

internal data class InventoryResult(
    val assets: Map<AssetId, ZipEntry>,
    val errors: List<DocumentError>,
)
