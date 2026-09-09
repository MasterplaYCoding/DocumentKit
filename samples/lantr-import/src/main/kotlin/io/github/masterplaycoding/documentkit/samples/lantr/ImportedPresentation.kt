package io.github.masterplaycoding.documentkit.samples.lantr

import io.github.masterplaycoding.documentkit.AssetId
import io.github.masterplaycoding.documentkit.DocumentCodec
import kotlinx.serialization.Serializable

/**
 * A deliberately small presentation model.
 *
 * This is *not* Lantr's `Presentation`. It carries the text and images a
 * reader would recognise as the content, and nothing else — no themes, no
 * rhetoric scoring, no audience profile, no colour overrides. Everything the
 * original file held and this model does not is reported by name rather than
 * dropped quietly; see [ImportReport.unsupported].
 *
 * Keeping the model this small is the point of the exercise. A converter that
 * tried to preserve everything would end up re-implementing the application it
 * is importing from, and would prove nothing about DocumentKit.
 */
@Serializable
public data class ImportedPresentation(
    val title: String,
    val author: String,
    val description: String,
    val sections: List<ImportedSection> = emptyList(),
)

/** One story block from the source arc, flattened to its slides. */
@Serializable
public data class ImportedSection(
    val name: String,
    val mainIdea: String,
    val slides: List<ImportedSlide> = emptyList(),
)

@Serializable
public data class ImportedSlide(
    val id: String,
    val blocks: List<ImportedBlock> = emptyList(),
)

/**
 * A single piece of slide content.
 *
 * A `kind` field rather than a sealed hierarchy: the shape is fixed at two
 * cases and stays legible in the stored JSON, which matters more here than
 * type-level elegance for a format someone may have to read by hand.
 */
@Serializable
public data class ImportedBlock(
    /** `"text"` or `"image"`. */
    val kind: String,
    val text: String? = null,
    /** Asset id in the produced container, for `image` blocks. */
    val assetId: String? = null,
)

/** Something in the source file this importer chose not to carry across. */
public data class UnsupportedContent(
    /** Where it was, e.g. `slides/slide_002.json`. */
    val where: String,
    /** What it was, in the reader's terms. */
    val what: String,
)

public val importedPresentationCodec: DocumentCodec<ImportedPresentation> = DocumentCodec(
    applicationId = "lantr.imported.presentation",
    schemaVersion = 1,
    serializer = ImportedPresentation.serializer(),
    referencedAssets = { presentation ->
        presentation.sections
            .flatMap { it.slides }
            .flatMap { it.blocks }
            .mapNotNull { block -> block.assetId?.let(AssetId::of) }
            .toSet()
    },
    validate = { presentation ->
        if (presentation.title.isBlank()) "an imported presentation needs a title" else null
    },
)
