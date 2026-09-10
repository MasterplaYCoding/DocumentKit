package io.github.masterplaycoding.documentkit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What [DocumentKitFormat] classifies, and — more importantly — what it does
 * not.
 */
class FormatTest {

    @Test
    fun recognisesTheEntriesTheFormatDefines() {
        assertTrue(DocumentKitFormat.isKnownEntry(DocumentKitFormat.MANIFEST_ENTRY))
        assertTrue(DocumentKitFormat.isKnownEntry(DocumentKitFormat.DOCUMENT_ENTRY))
        assertTrue(DocumentKitFormat.isKnownEntry("assets/cover"))
    }

    @Test
    fun rejectsEntriesOutsideTheFormat() {
        for (name in listOf("notes.txt", "", "Manifest.json", "MANIFEST.JSON", "asset/cover")) {
            assertFalse(DocumentKitFormat.isKnownEntry(name), "should not recognise '$name'")
        }
    }

    @Test
    fun matchesCaseSensitively() {
        // A case-insensitive match would let a second, differently-cased copy
        // sit beside the real entry, with the winner decided by the reader's
        // filesystem rather than by the file.
        assertFalse(DocumentKitFormat.isKnownEntry("Document.json"))
        assertFalse(DocumentKitFormat.isKnownEntry("Assets/cover"))
    }

    /**
     * The one that matters: [DocumentKitFormat.isKnownEntry] is a shape filter,
     * not a safety check.
     *
     * It answers "does this name look like part of the layout", and a
     * traversal sequence after the prefix looks exactly like that. Nothing is
     * wrong with the reader - it never turns an entry name into a path.
     * [DocumentKitFormat.assetPath] derives the path from an already-validated
     * [AssetId], so an entry whose name is not one of those derived paths has
     * nothing to attach itself to and is refused on that basis instead.
     *
     * This is pinned because the *name* of the function invites the opposite
     * assumption. Anyone reaching for it as a gate should find this test.
     */
    @Test
    fun isNotASafetyCheckAndDoesNotClaimToBe() {
        assertTrue(DocumentKitFormat.isKnownEntry("assets/../../escape.txt"))
        assertTrue(DocumentKitFormat.isKnownEntry("assets/"))
        assertTrue(DocumentKitFormat.isKnownEntry("assets//empty-segment"))

        // None of those is a path this format would ever produce, which is
        // what actually keeps them out.
        val derived = DocumentKitFormat.assetPath(AssetId.of("cover"))
        assertEquals("assets/cover", derived)
        assertTrue(AssetId.parseOrNull("../../escape.txt") == null)
        assertTrue(AssetId.parseOrNull("") == null)
    }

    @Test
    fun derivesAssetPathsFromTheIdAndNothingElse() {
        for (id in listOf("cover", "image-1", "a.png", "x".repeat(AssetId.MAX_LENGTH))) {
            assertEquals(
                DocumentKitFormat.ASSET_PREFIX + id,
                DocumentKitFormat.assetPath(AssetId.of(id)),
            )
        }
    }

    @Test
    fun theContainerVersionIsTheOneManifestsAreCheckedAgainst() {
        assertTrue(DocumentKitFormat.CONTAINER_VERSION >= 1)

        val newer = Manifest(
            containerVersion = DocumentKitFormat.CONTAINER_VERSION + 1,
            applicationId = "example.notebook",
            schemaVersion = 1,
            documentId = "doc-1",
            documentLength = 0,
            documentSha256 = "a".repeat(64),
        )

        assertTrue(newer.validate().any { it is DocumentError.UnsupportedContainer })
    }
}
