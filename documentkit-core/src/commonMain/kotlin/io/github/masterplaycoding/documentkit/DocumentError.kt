package io.github.masterplaycoding.documentkit

/**
 * Why an operation on a document failed.
 *
 * These are *expected* failures: a file someone sent you is damaged, was
 * written by another application, or is newer than this build understands.
 * Programming mistakes - a malformed asset id in your own code, a migration
 * registered twice - stay exceptions, because they are bugs to fix rather
 * than conditions to handle.
 *
 * Each error names the entry or migration step it concerns. It does not carry
 * document contents: an error message frequently ends up in a log or a bug
 * report, and the user's document is theirs.
 */
public sealed class DocumentError {
    /** Stable, machine-readable category. Safe to branch on. */
    public abstract val code: String

    /** Human-readable detail. Wording may change between releases. */
    public abstract val detail: String

    override fun toString(): String = "$code: $detail"

    /** The container version is newer than this build supports. */
    public data class UnsupportedContainer(val found: Int, val supported: Int) : DocumentError() {
        override val code: String get() = "UnsupportedContainer"
        override val detail: String
            get() = "container version $found, this build supports up to $supported"
    }

    /** The document belongs to a different application. */
    public data class WrongApplication(val found: String, val expected: String) : DocumentError() {
        override val code: String get() = "WrongApplication"
        override val detail: String get() = "document belongs to '$found', expected '$expected'"
    }

    /** The application schema version is newer than the codec understands. */
    public data class UnsupportedSchema(val found: Int, val supported: Int) : DocumentError() {
        override val code: String get() = "UnsupportedSchema"
        override val detail: String
            get() = "document schema version $found, this codec supports up to $supported"
    }

    /** No registered migration advances from [from] to the next version. */
    public data class MissingMigration(val from: Int, val to: Int) : DocumentError() {
        override val code: String get() = "MissingMigration"
        override val detail: String get() = "no registered migration from schema $from to $to"
    }

    /** A migration ran and failed. The source archive is untouched. */
    public data class MigrationFailed(
        val step: String,
        val from: Int,
        val to: Int,
        val reason: String,
    ) : DocumentError() {
        override val code: String get() = "MigrationFailed"
        override val detail: String get() = "migration '$step' ($from to $to) failed: $reason"
    }

    /** The manifest is missing, malformed, or internally inconsistent. */
    public data class InvalidManifest(val reason: String) : DocumentError() {
        override val code: String get() = "InvalidManifest"
        override val detail: String get() = reason
    }

    /** A required archive entry is absent. */
    public data class MissingEntry(val entry: String) : DocumentError() {
        override val code: String get() = "MissingEntry"
        override val detail: String get() = "archive has no entry '$entry'"
    }

    /** The archive declares the same entry name more than once. */
    public data class DuplicateEntry(val entry: String) : DocumentError() {
        override val code: String get() = "DuplicateEntry"
        override val detail: String get() = "archive declares '$entry' more than once"
    }

    /** An entry name is outside the format, or is not a plain file path. */
    public data class InvalidEntry(val entry: String, val reason: String) : DocumentError() {
        override val code: String get() = "InvalidEntry"
        override val detail: String get() = "entry '$entry': $reason"
    }

    /** A configured limit was reached. Reading stopped at that point. */
    public data class LimitExceeded(val limit: String, val allowed: Long) : DocumentError() {
        override val code: String get() = "LimitExceeded"
        override val detail: String get() = "$limit exceeded its limit of $allowed"
    }

    /** JSON was absent, not UTF-8, too deeply nested, or malformed. */
    public data class InvalidJson(val entry: String, val reason: String) : DocumentError() {
        override val code: String get() = "InvalidJson"
        override val detail: String get() = "entry '$entry' is not valid document JSON: $reason"
    }

    /**
     * Content did not match the manifest's declared length or digest.
     *
     * This detects corruption and mismatched content. It does not authenticate
     * an author: anyone who can rewrite the content can rewrite the manifest.
     */
    public data class IntegrityMismatch(
        val entry: String,
        val expected: String,
        val actual: String,
    ) : DocumentError() {
        override val code: String get() = "IntegrityMismatch"
        override val detail: String get() = "entry '$entry' expected $expected but found $actual"
    }

    /** The model references an asset the container does not contain. */
    public data class MissingReferencedAsset(val assetId: String) : DocumentError() {
        override val code: String get() = "MissingReferencedAsset"
        override val detail: String get() = "document references asset '$assetId', which is absent"
    }

    /** The application's own validation rejected the decoded model. */
    public data class ApplicationValidationFailed(val reason: String) : DocumentError() {
        override val code: String get() = "ApplicationValidationFailed"
        override val detail: String get() = reason
    }

    /**
     * The destination cannot be replaced atomically.
     *
     * Reported rather than silently downgraded to a non-atomic move: a caller
     * who asked for atomic replacement and got a truncated file instead would
     * have no way to know.
     */
    public data class AtomicReplaceUnsupported(val destination: String, val reason: String) :
        DocumentError() {
        override val code: String get() = "AtomicReplaceUnsupported"
        override val detail: String
            get() = "cannot atomically replace '$destination': $reason"
    }

    /** Underlying read, write or provider failure. */
    public data class IoFailure(val operation: String, val reason: String) : DocumentError() {
        override val code: String get() = "IoFailure"
        override val detail: String get() = "$operation failed: $reason"
    }
}

/**
 * Thrown when an operation that has no result to return fails with a
 * [DocumentError]. Operations that can report failure in their return type do
 * so instead.
 */
public class DocumentException(public val error: DocumentError) : Exception(error.toString())
