package io.github.masterplaycoding.documentkit

/**
 * The DocumentKit container layout, version 1.
 *
 * ```
 * document.dkit
 * ├── manifest.json
 * ├── document.json
 * └── assets/
 *     ├── <asset-id>
 *     └── <asset-id>
 * ```
 *
 * Applications choose their own file extension. The layout is fixed.
 */
public object DocumentKitFormat {
    /** The container version this build writes and is the maximum it reads. */
    public const val CONTAINER_VERSION: Int = 1

    public const val MANIFEST_ENTRY: String = "manifest.json"
    public const val DOCUMENT_ENTRY: String = "document.json"
    public const val ASSET_PREFIX: String = "assets/"

    /**
     * Archive paths are *derived* from asset ids, never supplied by a caller.
     *
     * A container is an untrusted input, and turning attacker-influenced text
     * into a path is how archive extraction goes wrong. Since the reader
     * computes the expected path for each declared asset id and matches
     * against that, a crafted entry name has nothing to attach itself to.
     */
    public fun assetPath(id: AssetId): String = ASSET_PREFIX + id.value

    /**
     * True for names shaped like part of this layout. A filter, not a check.
     *
     * "assets/../../escape.txt" starts with the prefix, so it passes here. It
     * is not a path this format can produce, and that is what keeps it out:
     * [assetPath] derives every asset path from an already-validated
     * [AssetId], and an entry matching none of those derived paths is
     * refused. Reaching for this function as a safety gate would be a
     * mistake - see FormatTest, which pins exactly that.
     */
    public fun isKnownEntry(name: String): Boolean =
        name == MANIFEST_ENTRY || name == DOCUMENT_ENTRY || name.startsWith(ASSET_PREFIX)
}
