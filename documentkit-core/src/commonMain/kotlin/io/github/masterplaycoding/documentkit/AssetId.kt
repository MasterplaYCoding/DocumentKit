package io.github.masterplaycoding.documentkit

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

/**
 * A stable, opaque identifier for one binary asset.
 *
 * The character set is deliberately narrow - ASCII letters, digits, `-`, `_`
 * and `.` - and the length is capped, because an asset id becomes a path
 * inside the archive. Restricting the id is what makes deriving that path
 * safe; validating the path afterwards would be a weaker guarantee applied
 * later.
 *
 * A leading dot and the names `.` and `..` are rejected outright, since those
 * are the ones that mean something to a filesystem.
 *
 * Ids are not derived from content. Two assets with identical bytes are two
 * assets unless the application decides otherwise.
 */
@Serializable
@JvmInline
public value class AssetId private constructor(public val value: String) {

    override fun toString(): String = value

    public companion object {
        public const val MAX_LENGTH: Int = 128

        private val ALLOWED = Regex("^[A-Za-z0-9._-]{1,$MAX_LENGTH}$")

        /**
         * Returns the id, or null when [value] is not a legal asset id.
         *
         * Prefer this in reading paths: an invalid id in a container someone
         * else wrote is a document error to report, not a programming mistake
         * to throw for.
         */
        public fun parseOrNull(value: String): AssetId? = when {
            !ALLOWED.matches(value) -> null
            value == "." || value == ".." -> null
            value.startsWith(".") -> null
            else -> AssetId(value)
        }

        /**
         * Returns the id, or throws [IllegalArgumentException].
         *
         * Prefer this when *your own* code constructs the id: passing a
         * malformed literal is a bug in the caller, not a document problem.
         */
        public fun of(value: String): AssetId = requireNotNull(parseOrNull(value)) {
            "invalid asset id: '$value' (allowed: 1-$MAX_LENGTH characters of " +
                "A-Z a-z 0-9 . _ -, not starting with a dot)"
        }
    }
}
