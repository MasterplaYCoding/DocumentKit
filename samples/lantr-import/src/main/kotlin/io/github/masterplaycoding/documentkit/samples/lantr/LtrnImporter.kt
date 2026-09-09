package io.github.masterplaycoding.documentkit.samples.lantr

import io.github.masterplaycoding.documentkit.AssetId
import io.github.masterplaycoding.documentkit.io.AssetSource
import java.io.File
import java.util.zip.ZipFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** What an import produced, and what it declined to carry across. */
public data class ImportReport(
    val presentation: ImportedPresentation,
    /** Assets to write into the container, keyed by their new id. */
    val assets: Map<AssetId, AssetSource>,
    /**
     * Everything in the source this importer did not preserve.
     *
     * The list is the deliverable, not an afterthought. A converter that
     * silently drops two thirds of a file and reports success has told the user
     * something false; one that hands back an itemised list of what it left
     * behind lets them decide whether the conversion was acceptable.
     */
    val unsupported: List<UnsupportedContent>,
)

/**
 * Reads a Lantr `.ltrn` archive and produces a DocumentKit presentation.
 *
 * One direction only. There is no `.ltrn` writer and no promise that a
 * round trip is possible, because it is not: this deliberately discards most
 * of what Lantr stores.
 *
 * **The source file is only ever read.** Nothing here opens it for writing,
 * and the caller chooses a separate destination.
 */
public object LtrnImporter {

    private val json = Json { ignoreUnknownKeys = true }

    /** Fields this importer knows about and deliberately does not carry. */
    private val droppedMetadata = listOf(
        "duration", "audience", "purpose", "created_at", "modified_at",
    )

    public fun import(source: File): ImportReport {
        require(source.isFile) { "not a file: ${source.path}" }

        val unsupported = mutableListOf<UnsupportedContent>()
        val assets = mutableMapOf<AssetId, AssetSource>()

        ZipFile(source).use { archive ->
            val metadata = readObject(archive, "metadata.json")
                ?: error("${source.name} has no metadata.json; is it a Lantr archive?")

            for (field in droppedMetadata) {
                if (metadata.containsKey(field)) {
                    unsupported += UnsupportedContent("metadata.json", field)
                }
            }
            if (readObject(archive, "theme.json") != null) {
                unsupported += UnsupportedContent(
                    "theme.json",
                    "colours, fonts and text styles (this model carries content only)",
                )
            }

            val images = importImages(archive, source, assets, unsupported)
            val slides = readSlides(archive, images, unsupported)
            val sections = readSections(archive, slides, unsupported)

            return ImportReport(
                presentation = ImportedPresentation(
                    title = metadata.string("title") ?: source.nameWithoutExtension,
                    author = metadata.string("author").orEmpty(),
                    description = metadata.string("description").orEmpty(),
                    sections = sections,
                ),
                assets = assets,
                unsupported = unsupported,
            )
        }
    }

    /**
     * Copies every entry under `images/` into assets, keyed by a validated id.
     *
     * Lantr's names come from the file system, so they can contain anything.
     * A name that is not a legal [AssetId] is reported and skipped rather than
     * silently mangled into one that happens to fit — renaming a user's asset
     * without telling them breaks the reference in a way nobody can trace.
     */
    private fun importImages(
        archive: ZipFile,
        source: File,
        assets: MutableMap<AssetId, AssetSource>,
        unsupported: MutableList<UnsupportedContent>,
    ): Map<String, AssetId> {
        val byArchiveName = mutableMapOf<String, AssetId>()

        for (entry in archive.entries().asSequence()) {
            if (entry.isDirectory || !entry.name.startsWith("images/")) continue

            val name = entry.name.removePrefix("images/")
            val id = AssetId.parseOrNull(name)
            if (id == null) {
                unsupported += UnsupportedContent(
                    entry.name,
                    "image name is not a valid asset id, so the image was not imported",
                )
                continue
            }

            // Read eagerly: the ZipFile handle closes when import() returns,
            // and the caller writes assets afterwards. These are slide images,
            // so the sizes are the ones Lantr already held in memory.
            val bytes = ZipFile(source).use { handle ->
                handle.getInputStream(handle.getEntry(entry.name)).use { it.readBytes() }
            }
            assets[id] = AssetSource.ofBytes(bytes)
            byArchiveName[name] = id
        }

        return byArchiveName
    }

    private fun readSlides(
        archive: ZipFile,
        images: Map<String, AssetId>,
        unsupported: MutableList<UnsupportedContent>,
    ): Map<String, ImportedSlide> {
        val slides = mutableMapOf<String, ImportedSlide>()

        for (entry in archive.entries().asSequence().sortedBy { it.name }) {
            if (entry.isDirectory || !entry.name.startsWith("slides/")) continue

            val slide = readObject(archive, entry.name) ?: continue
            val id = slide.string("id") ?: continue

            if (slide.containsKey("override_color")) {
                unsupported += UnsupportedContent(entry.name, "slide colour override")
            }
            if (slide.string("content_arrangement") != null) {
                unsupported += UnsupportedContent(entry.name, "content arrangement")
            }

            val blocks = slide["blocks"]?.jsonArray.orEmpty().mapNotNull { element ->
                readBlock(element.jsonObject, entry.name, images, unsupported)
            }

            slides[id] = ImportedSlide(id = id, blocks = blocks)
        }

        return slides
    }

    private fun readBlock(
        block: JsonObject,
        where: String,
        images: Map<String, AssetId>,
        unsupported: MutableList<UnsupportedContent>,
    ): ImportedBlock? = when (val kind = block.string("block_type")) {
        "text" -> {
            if (block.containsKey("override_color")) {
                unsupported += UnsupportedContent(where, "text block colour override")
            }
            ImportedBlock(kind = "text", text = block.string("content").orEmpty())
        }

        "image" -> {
            unsupported += UnsupportedContent(where, "image height and corner radius")
            val path = block.string("image_path").orEmpty()
            val id = images[path]
            if (id == null) {
                // A reference with no image behind it. Reported rather than
                // written as a dangling asset id, which the codec would reject
                // on open anyway - failing later and less clearly.
                unsupported += UnsupportedContent(
                    where,
                    "image reference '$path' has no matching file in images/",
                )
                null
            } else {
                ImportedBlock(kind = "image", assetId = id.value)
            }
        }

        else -> {
            unsupported += UnsupportedContent(where, "block of unsupported type '${kind ?: "?"}'")
            null
        }
    }

    private fun readSections(
        archive: ZipFile,
        slides: Map<String, ImportedSlide>,
        unsupported: MutableList<UnsupportedContent>,
    ): List<ImportedSection> {
        val order = readObject(archive, "arc/blocks.json")
            ?.get("block_ids")?.jsonArray.orEmpty()
            .mapNotNull { it.jsonPrimitive.contentOrNull }

        val byId = mutableMapOf<String, JsonObject>()
        for (entry in archive.entries().asSequence().sortedBy { it.name }) {
            if (entry.isDirectory || !entry.name.startsWith("arc/blocks/")) continue
            val block = readObject(archive, entry.name) ?: continue
            block.string("id")?.let { byId[it] = block }

            if (block.containsKey("rhetoric")) {
                unsupported += UnsupportedContent(entry.name, "rhetoric scoring")
            }
            if (block.containsKey("block_type")) {
                unsupported += UnsupportedContent(entry.name, "story block type")
            }
            if (block.containsKey("category")) {
                unsupported += UnsupportedContent(entry.name, "story section category")
            }
        }

        // The declared order is authoritative; anything not listed follows, so
        // a truncated index loses ordering rather than content.
        val ordered = order.mapNotNull(byId::get) +
            byId.filterKeys { it !in order }.values

        return ordered.map { block ->
            ImportedSection(
                name = block.string("name").orEmpty(),
                mainIdea = block.string("main_idea").orEmpty(),
                slides = block["slide_ids"]?.jsonArray.orEmpty()
                    .mapNotNull { it.jsonPrimitive.contentOrNull }
                    .mapNotNull(slides::get),
            )
        }
    }

    private fun readObject(archive: ZipFile, path: String): JsonObject? {
        val entry = archive.getEntry(path) ?: return null
        val text = archive.getInputStream(entry).use { it.readBytes() }.decodeToString()
        return runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    private fun <T> List<T>?.orEmpty(): List<T> = this ?: emptyList()
}
