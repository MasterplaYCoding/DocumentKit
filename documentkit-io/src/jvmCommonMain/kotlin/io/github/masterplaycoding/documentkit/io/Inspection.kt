package io.github.masterplaycoding.documentkit.io

import io.github.masterplaycoding.documentkit.AssetEntry
import io.github.masterplaycoding.documentkit.DocumentError
import io.github.masterplaycoding.documentkit.DocumentKitFormat

/**
 * What a container says about itself, read without decoding any application
 * model.
 *
 * Everything here comes from the manifest and the archive's entry list, so it
 * is available for a document belonging to an application this build knows
 * nothing about.
 */
public data class DocumentSummary(
    val containerVersion: Int,
    val applicationId: String,
    val schemaVersion: Int,
    val documentId: String,
    /** Declared length of `document.json`. */
    val documentLength: Long,
    val assets: List<AssetEntry>,
    /** Physical entries in the archive, including any this format rejects. */
    val entryCount: Int,
    /** Size of the container file itself. */
    val archiveBytes: Long,
) {
    /** Total declared uncompressed bytes. Declared, not verified — see [DocumentStore.validate]. */
    public val declaredContentBytes: Long
        get() = documentLength + assets.sumOf { it.length }
}

/** The outcome of checking a container's structure and integrity. */
public data class ValidationReport(
    /** Null when the manifest could not be read at all. */
    val summary: DocumentSummary?,
    /** Every problem found, not only the first. */
    val errors: List<DocumentError>,
    /** Entries whose actual bytes matched their declared length and digest. */
    val verifiedEntries: List<String>,
) {
    public val isValid: Boolean
        get() = errors.isEmpty()

    /**
     * Why this is not the same as "the document is usable".
     *
     * Validation without an application codec checks the *container*: entry
     * names, the manifest, actual lengths and SHA-256 digests. It cannot check
     * that `document.json` matches the application's schema, that a migration
     * chain reaches it, or that the model satisfies whatever the application
     * considers valid — all of which need code this tool does not have.
     */
    public val scope: String
        get() = "container structure and integrity; application schema not checked"
}
