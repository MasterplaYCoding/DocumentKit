package io.github.masterplaycoding.documentkit

import kotlinx.serialization.json.JsonObject

/**
 * One step of a schema migration chain, advancing a document by exactly one
 * version.
 *
 * Migrations operate on [JsonObject], not on the application's model classes.
 * A migration written against version 3's data classes stops compiling the day
 * version 4 changes them, and a migration that no longer compiles is a
 * migration someone will "fix" by making it do the wrong thing. JSON in, JSON
 * out, forever.
 *
 * A migration must be a pure function. No file access, no network, no clock:
 * migrating the same document twice must produce the same result, and a
 * migration that reads the current time silently makes old documents
 * unreproducible.
 *
 * Assets are untouched in 1.0. A migration that needs to re-encode an image is
 * not a schema migration; it is a conversion the application should perform
 * explicitly.
 */
public interface DocumentMigration {
    /** Stable name, used in error reports. Changing it changes error output. */
    public val name: String

    /** The version this migration reads. */
    public val fromVersion: Int

    /** Always [fromVersion] + 1. */
    public val toVersion: Int get() = fromVersion + 1

    /** Transforms the document body. Must not mutate [document]. */
    public fun migrate(document: JsonObject): JsonObject
}

/** Convenience constructor for a migration defined by a lambda. */
public fun documentMigration(
    name: String,
    fromVersion: Int,
    migrate: (JsonObject) -> JsonObject,
): DocumentMigration = object : DocumentMigration {
    override val name: String = name
    override val fromVersion: Int = fromVersion
    override fun migrate(document: JsonObject): JsonObject = migrate(document)
}

/**
 * An ordered, gap-free chain of migrations.
 *
 * The chain is validated when it is built rather than when a document arrives.
 * A missing or duplicated step is a mistake in the application's own code, so
 * it should fail at construction - which happens the first time a developer
 * runs anything - not on a user's machine when they open an old file.
 */
public class MigrationChain(migrations: List<DocumentMigration>, public val targetVersion: Int) {

    private val byFromVersion: Map<Int, DocumentMigration>

    init {
        require(targetVersion >= 1) { "targetVersion must be at least 1" }

        val seen = mutableMapOf<Int, DocumentMigration>()
        for (migration in migrations) {
            require(migration.fromVersion >= 1) {
                "migration '${migration.name}' has a non-positive fromVersion"
            }
            require(migration.toVersion == migration.fromVersion + 1) {
                "migration '${migration.name}' must advance exactly one version, " +
                    "but goes ${migration.fromVersion} to ${migration.toVersion}"
            }
            require(migration.toVersion <= targetVersion) {
                "migration '${migration.name}' targets schema ${migration.toVersion}, " +
                    "beyond the codec's version $targetVersion"
            }
            val existing = seen.put(migration.fromVersion, migration)
            require(existing == null) {
                "two migrations start from schema ${migration.fromVersion}: " +
                    "'${existing?.name}' and '${migration.name}'"
            }
        }
        byFromVersion = seen
    }

    /** The lowest schema version this chain can bring up to date. */
    public val oldestSupportedVersion: Int
        get() {
            var version = targetVersion
            while (byFromVersion.containsKey(version - 1)) {
                version -= 1
            }
            return version
        }

    /**
     * Migrates [document] from [fromVersion] up to [targetVersion].
     *
     * Returns the failing step rather than a partially migrated model: half a
     * migration is not a document, and handing one back would invite an
     * application to save it.
     */
    public fun migrate(document: JsonObject, fromVersion: Int): MigrationResult {
        if (fromVersion > targetVersion) {
            return MigrationResult.Failure(
                DocumentError.UnsupportedSchema(fromVersion, targetVersion),
            )
        }

        var current = document
        var version = fromVersion
        val applied = mutableListOf<String>()

        while (version < targetVersion) {
            val step = byFromVersion[version]
                ?: return MigrationResult.Failure(
                    DocumentError.MissingMigration(version, version + 1),
                )

            current = try {
                step.migrate(current)
            } catch (cause: Exception) {
                return MigrationResult.Failure(
                    DocumentError.MigrationFailed(
                        step = step.name,
                        from = step.fromVersion,
                        to = step.toVersion,
                        reason = cause.message ?: cause::class.simpleName.orEmpty(),
                    ),
                )
            }

            applied += step.name
            version = step.toVersion
        }

        return MigrationResult.Success(current, applied)
    }
}

/** Outcome of running a [MigrationChain]. */
public sealed class MigrationResult {
    /** The document reached the target version. [applied] names each step run. */
    public data class Success(
        val document: JsonObject,
        val applied: List<String>,
    ) : MigrationResult()

    /** The chain stopped. The source document is unchanged. */
    public data class Failure(val error: DocumentError) : MigrationResult()
}
