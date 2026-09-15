package io.github.masterplaycoding.documentkit.samples.lantr

import io.github.masterplaycoding.documentkit.AssetId
import io.github.masterplaycoding.documentkit.io.AssetSource
import java.io.EOFException
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
 * The file cannot be imported at all: it is not a ZIP archive, or it has no
 * readable `metadata.json`, which every Lantr archive has.
 *
 * That is the only refusal. Anything short of it - a slide that is not JSON, a
 * field of the wrong type, a reference to nothing - is imported as far as it
 * goes and named in [ImportReport.unsupported].
 */
public class LtrnImportException(message: String, cause: Throwable? = null) : Exception(message, cause)

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

    /** Fields this importer knows about and deliberately does not carry. */
    private val droppedMetadata = listOf(
        "duration", "audience", "purpose", "created_at", "modified_at",
    )

    /**
     * Imports [source].
     *
     * Every field is untrusted, because a `.ltrn` is somebody else's file. A
     * value of the wrong shape is reported and treated as absent rather than
     * thrown, so one damaged slide costs that slide - by name - not the import.
     *
     * @throws LtrnImportException when [source] is not a ZIP archive or has no
     *   readable `metadata.json`.
     * @throws java.io.IOException when the file cannot be read for a reason
     *   that is not its content.
     */
    public fun import(source: File): ImportReport {
        require(source.isFile) { "not a file: ${source.path}" }

        val archive = try {
            ZipFile(source)
        } catch (notZip: ZipException) {
            throw LtrnImportException("${source.name} is not a ZIP archive, so it is not a Lantr file", notZip)
        }
        return archive.use { Import(it, source).run() }
    }

    /** The outcome of reading one JSON entry. */
    private sealed interface Read {
        data object Missing : Read
        data class Unreadable(val why: String) : Read
        data class Found(val value: JsonObject) : Read
    }

    private class SourcedSlide(val where: String, val slide: ImportedSlide)

    /** Identity, not structure: two section files with the same JSON are two sections. */
    private class SourcedSection(val id: String?, val where: String, val json: JsonObject)

    /** One import: the archive, and everything it has declined to carry so far. */
    private class Import(private val archive: ZipFile, private val source: File) {
        private val unsupported = mutableListOf<UnsupportedContent>()
        private val assets = mutableMapOf<AssetId, AssetSource>()

        fun run(): ImportReport {
            val metadata = when (val read = readObject("metadata.json")) {
                Read.Missing -> throw LtrnImportException("${source.name} has no metadata.json; is it a Lantr archive?")
                is Read.Unreadable -> throw LtrnImportException(
                    "${source.name} has a metadata.json that is ${read.why}, so it cannot be imported",
                )
                is Read.Found -> read.value
            }

            for (field in droppedMetadata) {
                if (metadata.containsKey(field)) report("metadata.json", field)
            }
            if (archive.getEntry("theme.json") != null) {
                report("theme.json", "colours, fonts and text styles (this model carries content only)")
            }

            val images = importImages()
            val slides = readSlides(images)
            val sections = readSections(slides)

            return ImportReport(
                presentation = ImportedPresentation(
                    title = title(metadata),
                    author = text(metadata, "author", "metadata.json").orEmpty(),
                    description = text(metadata, "description", "metadata.json").orEmpty(),
                    sections = sections,
                ),
                assets = assets,
                unsupported = unsupported,
            )
        }

        private fun report(where: String, what: String) {
            unsupported += UnsupportedContent(where, what)
        }

        /**
         * The declared title, or the file name when there is none.
         *
         * The container needs a non-blank title, so passing a blank one through
         * would import cleanly and then fail to save - later, and far from the
         * cause.
         */
        private fun title(metadata: JsonObject): String {
            val declared = text(metadata, "title", "metadata.json")
            if (declared != null && declared.isNotBlank()) return declared
            val fallback = source.nameWithoutExtension.ifBlank { "Untitled" }
            if (declared != null) report("metadata.json", "the title is blank, so '$fallback' was used instead")
            return fallback
        }

        /**
         * Copies every entry under `images/` into assets, keyed by a validated id.
         *
         * Lantr's names come from the file system, so they can contain anything.
         * A name that is not a legal [AssetId] is reported and skipped rather than
         * silently mangled into one that happens to fit — renaming a user's asset
         * without telling them breaks the reference in a way nobody can trace.
         */
        private fun importImages(): Map<String, AssetId> {
            val byArchiveName = mutableMapOf<String, AssetId>()

            for (entry in archive.entries().asSequence()) {
                if (entry.isDirectory || !entry.name.startsWith("images/")) continue

                val name = entry.name.removePrefix("images/")
                val id = AssetId.parseOrNull(name)
                if (id == null) {
                    report(entry.name, "image name is not a valid asset id, so the image was not imported")
                    continue
                }

                // Read eagerly: the archive closes when import() returns, and the
                // caller writes assets afterwards. These are slide images, so the
                // sizes are the ones Lantr already held in memory.
                val bytes = bytesOf(entry)
                if (bytes == null) {
                    report(entry.name, "the image is damaged in the archive, so it was not imported")
                    continue
                }
                assets[id] = AssetSource.ofBytes(bytes)
                byArchiveName[name] = id
            }

            return byArchiveName
        }

        private fun readSlides(images: Map<String, AssetId>): Map<String, SourcedSlide> {
            val slides = linkedMapOf<String, SourcedSlide>()

            for (entry in archive.entries().asSequence().sortedBy { it.name }) {
                if (entry.isDirectory || !entry.name.startsWith("slides/")) continue
                val where = entry.name

                val slide = when (val read = readObject(where)) {
                    Read.Missing -> continue
                    is Read.Unreadable -> {
                        report(where, "the slide is ${read.why}, so it was not imported")
                        continue
                    }
                    is Read.Found -> read.value
                }

                val id = text(slide, "id", where)
                if (id == null) {
                    report(where, "the slide has no id, so no section can list it and it was not imported")
                    continue
                }
                val earlier = slides[id]
                if (earlier != null) {
                    report(where, "slide id '$id' is already used by ${earlier.where}, so this slide was not imported")
                    continue
                }

                if (slide.containsKey("override_color")) report(where, "slide colour override")
                if (slide.containsKey("content_arrangement")) report(where, "content arrangement")

                val blocks = list(slide, "blocks", where).mapNotNull { element ->
                    if (element is JsonObject) {
                        readBlock(element, where, images)
                    } else {
                        report(where, "a block is not a JSON object, so it was not imported")
                        null
                    }
                }

                slides[id] = SourcedSlide(where, ImportedSlide(id = id, blocks = blocks))
            }

            return slides
        }

        private fun readBlock(
            block: JsonObject,
            where: String,
            images: Map<String, AssetId>,
        ): ImportedBlock? = when (val kind = text(block, "block_type", where)) {
            "text" -> {
                if (block.containsKey("override_color")) report(where, "text block colour override")
                ImportedBlock(kind = "text", text = text(block, "content", where).orEmpty())
            }

            "image" -> {
                report(where, "image height and corner radius")
                val path = text(block, "image_path", where).orEmpty()
                val id = images[path]
                if (id == null) {
                    // A reference with no image behind it. Reported rather than
                    // written as a dangling asset id, which the codec would reject
                    // on open anyway - failing later and less clearly.
                    report(where, "image reference '$path' has no matching file in images/")
                    null
                } else {
                    ImportedBlock(kind = "image", assetId = id.value)
                }
            }

            else -> {
                report(where, "block of unsupported type '${kind ?: "?"}'")
                null
            }
        }

        private fun readSections(slides: Map<String, SourcedSlide>): List<ImportedSection> {
            val order = when (val read = readObject("arc/blocks.json")) {
                Read.Missing -> emptyList()
                is Read.Unreadable -> {
                    report("arc/blocks.json", "the section order is ${read.why}, so sections follow file order")
                    emptyList()
                }
                is Read.Found -> ids(read.value, "block_ids", "arc/blocks.json")
            }

            val found = mutableListOf<SourcedSection>()
            for (entry in archive.entries().asSequence().sortedBy { it.name }) {
                if (entry.isDirectory || !entry.name.startsWith("arc/blocks/")) continue
                val where = entry.name

                val block = when (val read = readObject(where)) {
                    Read.Missing -> continue
                    is Read.Unreadable -> {
                        report(where, "the section is ${read.why}, so it was not imported")
                        continue
                    }
                    is Read.Found -> read.value
                }

                if (block.containsKey("rhetoric")) report(where, "rhetoric scoring")
                if (block.containsKey("block_type")) report(where, "story block type")
                if (block.containsKey("category")) report(where, "story section category")

                found += SourcedSection(text(block, "id", where), where, block)
            }

            // The declared order is authoritative. Anything not listed, or with no
            // id to list it by, follows in file order, so a damaged index loses
            // ordering rather than content.
            val listed = order.mapNotNull { id ->
                val section = found.firstOrNull { it.id == id }
                if (section == null) report("arc/blocks.json", "section '$id' is listed but has no file in arc/blocks/")
                section
            }.distinct()
            val ordered = listed + found.filter { it !in listed }

            val listedSlides = mutableSetOf<String>()
            val sections = ordered.map { section ->
                ImportedSection(
                    name = text(section.json, "name", section.where).orEmpty(),
                    mainIdea = text(section.json, "main_idea", section.where).orEmpty(),
                    slides = ids(section.json, "slide_ids", section.where).mapNotNull { slideId ->
                        val slide = slides[slideId]
                        if (slide == null) {
                            report(section.where, "slide '$slideId' has no file in slides/, so the section does not include it")
                        } else {
                            listedSlides.add(slideId)
                        }
                        slide?.slide
                    },
                )
            }

            // A slide no section lists has nowhere to go in this model.
            for ((id, slide) in slides) {
                if (id !in listedSlides) report(slide.where, "no section lists this slide, so it was not imported")
            }

            return sections
        }

        private fun readObject(path: String): Read {
            val entry = archive.getEntry(path) ?: return Read.Missing
            val bytes = bytesOf(entry) ?: return Read.Unreadable("damaged in the archive")
            val element = try {
                Json.parseToJsonElement(bytes.decodeToString())
            } catch (invalid: SerializationException) {
                return Read.Unreadable("not valid JSON")
            }
            return if (element is JsonObject) Read.Found(element) else Read.Unreadable("not a JSON object")
        }

        /**
         * An entry's bytes, or null when the archive is damaged inside it.
         *
         * `java.util.zip` reads an entry's local header only when its bytes are
         * first read, so an archive whose directory lists entries cleanly can
         * still fail here. DocumentKit's reader learned that from its fuzzer.
         */
        private fun bytesOf(entry: ZipEntry): ByteArray? = try {
            archive.getInputStream(entry).use { it.readBytes() }
        } catch (damaged: ZipException) {
            null
        } catch (truncated: EOFException) {
            null
        }

        /**
         * The text at [key], or null when it is absent or JSON null. Numbers and
         * booleans are taken as their text; an object or a list is reported and
         * treated as absent.
         */
        private fun text(json: JsonObject, key: String, where: String): String? = when (val value = json[key]) {
            null, JsonNull -> null
            is JsonPrimitive -> value.content
            else -> {
                report(where, "'$key' is not text, so it was not imported")
                null
            }
        }

        /** The list at [key], empty when it is absent or JSON null; any other value is reported. */
        private fun list(json: JsonObject, key: String, where: String): List<JsonElement> = when (val value = json[key]) {
            null, JsonNull -> emptyList()
            is JsonArray -> value
            else -> {
                report(where, "'$key' is not a list, so nothing in it was imported")
                emptyList()
            }
        }

        private fun ids(json: JsonObject, key: String, where: String): List<String> =
            list(json, key, where).mapNotNull { element ->
                if (element is JsonPrimitive && element !is JsonNull) {
                    element.content
                } else {
                    report(where, "an entry in '$key' is not an id, so it was skipped")
                    null
                }
            }
    }
}
