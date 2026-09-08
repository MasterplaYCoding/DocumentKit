package io.github.masterplaycoding.documentkit

/**
 * Resource ceilings applied while reading and writing.
 *
 * Writers apply the same limits as readers. A writer allowed to produce a file
 * its own reader rejects is a trap that only shows up when the user tries to
 * open what they just saved.
 *
 * These bound *actual* streamed bytes, not the sizes an archive declares about
 * itself. A container is untrusted input; its own claims about how large it is
 * cannot be the protection against it being too large.
 */
public data class DocumentLimits(
    /** Maximum size of the container file itself. */
    val maxArchiveBytes: Long = 256L * 1024 * 1024,
    /** Maximum total uncompressed bytes actually read from the archive. */
    val maxTotalUncompressedBytes: Long = 256L * 1024 * 1024,
    /** Maximum number of archive entries. */
    val maxEntryCount: Int = 2_048,
    /** Maximum size of `manifest.json`. */
    val maxManifestBytes: Long = 1L * 1024 * 1024,
    /** Maximum size of `document.json`. */
    val maxDocumentBytes: Long = 8L * 1024 * 1024,
    /** Maximum size of any single asset. */
    val maxAssetBytes: Long = 64L * 1024 * 1024,
    /** Maximum JSON nesting depth, checked before decoding. */
    val maxJsonDepth: Int = 128,
) {
    init {
        require(maxArchiveBytes > 0) { "maxArchiveBytes must be positive" }
        require(maxTotalUncompressedBytes > 0) { "maxTotalUncompressedBytes must be positive" }
        require(maxEntryCount > 0) { "maxEntryCount must be positive" }
        require(maxManifestBytes > 0) { "maxManifestBytes must be positive" }
        require(maxDocumentBytes > 0) { "maxDocumentBytes must be positive" }
        require(maxAssetBytes > 0) { "maxAssetBytes must be positive" }
        require(maxJsonDepth > 0) { "maxJsonDepth must be positive" }
    }

    public companion object {
        /** The defaults documented in the format specification. */
        public val Default: DocumentLimits = DocumentLimits()
    }
}
