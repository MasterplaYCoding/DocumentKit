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

    /** True for the entry names this format defines. Everything else is rejected. */
    public fun isKnownEntry(name: String): Boolean =
        name == MANIFEST_ENTRY || name == DOCUMENT_ENTRY || name.startsWith(ASSET_PREFIX)
}
