package io.github.masterplaycoding.documentkit

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One asset's entry in the manifest index. */
@Serializable
public data class AssetEntry(
    val id: AssetId,
    /** Actual uncompressed byte length. */
    val length: Long,
    /** Lowercase hex SHA-256 of the asset's bytes. */
    val sha256: String,
    /** Optional, advisory. The library never decodes assets. */
    @SerialName("media_type") val mediaType: String? = null,
)

/**
 * The container's table of contents.
 *
 * Deliberately absent: timestamps and display metadata. A writer that stamps
 * the current time makes two saves of identical content differ, and makes the
 * library the authority on something the application should own. If a document
 * has a "last modified" date, it belongs in the application's own model.
 */
@Serializable
public data class Manifest(
    @SerialName("container_version") val containerVersion: Int,
    @SerialName("application_id") val applicationId: String,
    @SerialName("schema_version") val schemaVersion: Int,
    /**
     * Caller-supplied stable document identity.
     *
     * Never invented on read. A malformed id is an error to report, not a
     * problem to paper over with a fresh UUID - doing that silently turns one
     * document into a different document.
     */
    @SerialName("document_id") val documentId: String,
    @SerialName("document_length") val documentLength: Long,
    @SerialName("document_sha256") val documentSha256: String,
    val assets: List<AssetEntry> = emptyList(),
) {
    /**
     * Structural checks that need no archive access.
     *
     * Returns every problem found rather than only the first, because a
     * validation tool reporting one error per run is a poor experience when
     * a file is thoroughly broken.
     */
    public fun validate(): List<DocumentError> {
        val errors = mutableListOf<DocumentError>()

        if (containerVersion <= 0) {
            errors += DocumentError.InvalidManifest("container_version must be positive")
        }
        if (containerVersion > DocumentKitFormat.CONTAINER_VERSION) {
            errors += DocumentError.UnsupportedContainer(
                containerVersion,
                DocumentKitFormat.CONTAINER_VERSION,
            )
        }
        if (applicationId.isBlank()) {
            errors += DocumentError.InvalidManifest("application_id must not be blank")
        }
        if (schemaVersion <= 0) {
            errors += DocumentError.InvalidManifest("schema_version must be positive")
        }
        if (documentId.isBlank()) {
            errors += DocumentError.InvalidManifest("document_id must not be blank")
        }
        if (documentLength < 0) {
            errors += DocumentError.InvalidManifest("document_length must not be negative")
        }
        if (!isSha256(documentSha256)) {
            errors += DocumentError.InvalidManifest("document_sha256 is not a SHA-256 hex digest")
        }

        val seen = mutableSetOf<AssetId>()
        for (asset in assets) {
            if (!seen.add(asset.id)) {
                errors += DocumentError.InvalidManifest("asset '${asset.id}' is indexed twice")
            }
            if (asset.length < 0) {
                errors += DocumentError.InvalidManifest("asset '${asset.id}' has a negative length")
            }
            if (!isSha256(asset.sha256)) {
                errors += DocumentError.InvalidManifest(
                    "asset '${asset.id}' has no valid SHA-256 digest",
                )
            }
        }

        // Overflow-safe summation: a manifest is untrusted input, and a set of
        // lengths that wraps Long would otherwise pass a naive total check.
        //
        // Negative lengths are skipped rather than summed. They are already
        // reported above, and feeding one in here makes the guard itself
        // overflow - Long.MAX_VALUE minus a negative wraps to Long.MIN_VALUE,
        // so every total compares greater and the manifest is additionally
        // accused of an overflow that never happened.
        var total = documentLength.coerceAtLeast(0)
        for (asset in assets) {
            if (asset.length < 0) continue
            if (total > Long.MAX_VALUE - asset.length) {
                errors += DocumentError.InvalidManifest("declared lengths overflow")
                break
            }
            total += asset.length
        }

        return errors
    }

    /**
     * Total declared uncompressed size. Advisory only - readers count bytes.
     *
     * See the top-level [declaredTotalLength] for why this saturates rather
     * than sums, and prefer [validate], which reports a manifest whose lengths
     * do not add up as broken instead of quietly totalling it.
     */
    public fun declaredTotalLength(): Long = declaredTotalLength(documentLength, assets)

    private fun isSha256(value: String): Boolean =
        value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }
}

/**
 * Sums declared lengths without wrapping, ignoring negative ones.
 *
 * Shared rather than written twice, because it was written twice. [Manifest]
 * and `DocumentSummary` each carried their own `documentLength +
 * assets.sumOf { it.length }`, and both wrapped. A naive sum turns lengths
 * that add past `Long` into a small - or negative - number, which satisfies
 * any check phrased as "the declared total is under N". That is the only thing
 * an advisory total is ever used for.
 *
 * Neither copy was reachable with an overflowing manifest through the reading
 * paths - DocumentStore validates a manifest before summarising it - but both
 * types are public and constructible, so the arithmetic has to hold for values
 * that never came through a reader.
 *
 * Negative lengths are skipped rather than subtracted. A manifest carrying one
 * is broken and [Manifest.validate] says so; it must not additionally be able
 * to shrink a total someone is about to compare against a limit.
 */
public fun declaredTotalLength(documentLength: Long, assets: List<AssetEntry>): Long {
    var total = documentLength.coerceAtLeast(0)
    for (asset in assets) {
        if (asset.length < 0) continue
        if (total > Long.MAX_VALUE - asset.length) return Long.MAX_VALUE
        total += asset.length
    }
    return total
}
