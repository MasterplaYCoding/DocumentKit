package io.github.masterplaycoding.documentkit

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Everything DocumentKit needs to know about one application's model.
 *
 * The library owns the container, the integrity checks and the storage
 * guarantees. The application owns its data model, its rendering and its
 * editing operations. This type is the whole of the boundary between them.
 *
 * Nothing here depends on a UI toolkit. A codec is constructible from a plain
 * Kotlin module with `kotlinx.serialization` and nothing else, which is what
 * makes the same model usable from a desktop app, an Android app, a CLI and a
 * test.
 *
 * @param T the application's document model.
 */
public class DocumentCodec<T : Any>(
    /**
     * Identifies the application that owns this format, for example
     * `"example.notebook"`. Documents belonging to another application are
     * rejected rather than best-effort decoded.
     */
    public val applicationId: String,
    /** The schema version this build writes. */
    public val schemaVersion: Int,
    public val serializer: KSerializer<T>,
    migrations: List<DocumentMigration> = emptyList(),
    /**
     * The assets the model refers to.
     *
     * Used to reject a document that references an asset the container does
     * not hold, and to decide what must be written on save. Assets *not*
     * returned here are still preserved: an older build of an application
     * should not silently delete data a newer build added.
     */
    public val referencedAssets: (T) -> Set<AssetId> = { emptySet() },
    /**
     * The application's own semantic validation.
     *
     * Return null when the model is acceptable. This runs after decoding and
     * after any migration, so it always sees a current-schema model.
     */
    public val validate: (T) -> String? = { null },
) {
    init {
        require(applicationId.isNotBlank()) { "applicationId must not be blank" }
        require(schemaVersion >= 1) { "schemaVersion must be at least 1" }
    }

    public val migrations: MigrationChain = MigrationChain(migrations, schemaVersion)

    /**
     * The JSON configuration used for application documents.
     *
     * `ignoreUnknownKeys` is **off**. A field this build does not recognise
     * means the document came from somewhere this build does not fully
     * understand, and quietly dropping it loses user data on the next save.
     * Forward compatibility is what the schema version is for.
     */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private val json: Json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    /** Serialises the model to the bytes that become `document.json`. */
    public fun encode(document: T): ByteArray =
        json.encodeToString(serializer, document).encodeToByteArray()

    /**
     * Migrates and decodes a document body.
     *
     * The order is fixed: reject a future schema, migrate to the current one,
     * decode with the current serializer, then run the application's
     * validation. Validation therefore never sees a half-migrated model.
     */
    public fun decode(body: JsonObject, fromSchemaVersion: Int): DecodeResult<T> {
        if (fromSchemaVersion > schemaVersion) {
            return DecodeResult.Failure(
                DocumentError.UnsupportedSchema(fromSchemaVersion, schemaVersion),
            )
        }

        val migrated = when (val result = migrations.migrate(body, fromSchemaVersion)) {
            is MigrationResult.Failure -> return DecodeResult.Failure(result.error)
            is MigrationResult.Success -> result
        }

        val model = try {
            json.decodeFromJsonElement(serializer, migrated.document)
        } catch (cause: Exception) {
            return DecodeResult.Failure(
                DocumentError.InvalidJson(
                    DocumentKitFormat.DOCUMENT_ENTRY,
                    DecodeFailure.describe(cause, DocumentKitFormat.DOCUMENT_ENTRY),
                ),
            )
        }

        validate(model)?.let { reason ->
            return DecodeResult.Failure(DocumentError.ApplicationValidationFailed(reason))
        }

        return DecodeResult.Success(model, migrated.applied)
    }

    /** Parses document bytes into a [JsonObject], without decoding the model. */
    public fun parseBody(bytes: ByteArray): Result<JsonObject> = runCatching {
        json.parseToJsonElement(bytes.decodeToString()) as? JsonObject
            ?: error("document.json must contain a JSON object at the top level")
    }

}

/** Outcome of decoding a document body. */
public sealed class DecodeResult<out T> {
    /** [migrationsApplied] names each migration that ran, in order. */
    public data class Success<T>(val document: T, val migrationsApplied: List<String>) :
        DecodeResult<T>()

    public data class Failure(val error: DocumentError) : DecodeResult<Nothing>()
}
