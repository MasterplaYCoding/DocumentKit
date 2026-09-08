package io.github.masterplaycoding.documentkit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AssetIdTest {

    @Test
    fun acceptsOrdinaryIdentifiers() {
        for (candidate in listOf("cover", "image-1", "image_1", "a.png", "A1", "x".repeat(128))) {
            assertNotNull(AssetId.parseOrNull(candidate), "should accept '$candidate'")
        }
    }

    @Test
    fun rejectsAnythingThatCouldEscapeTheAssetsDirectory() {
        val hostile = listOf(
            "..",
            ".",
            "../secrets",
            "..\\secrets",
            "/etc/passwd",
            "assets/nested",
            "C:\\Windows\\System32",
            ".hidden",
            "with space",
            "with\u0000null",
            "emoji\uD83D\uDCA5",
        )

        for (candidate in hostile) {
            assertNull(AssetId.parseOrNull(candidate), "should reject '$candidate'")
        }
    }

    @Test
    fun rejectsEmptyAndOverlongIds() {
        assertNull(AssetId.parseOrNull(""))
        assertNull(AssetId.parseOrNull("x".repeat(AssetId.MAX_LENGTH + 1)))
    }

    @Test
    fun ofThrowsForCallerMistakes() {
        // parseOrNull is for untrusted input; of() is for our own literals,
        // where a bad id is a bug rather than a document problem.
        assertFailsWith<IllegalArgumentException> { AssetId.of("../nope") }
    }

    @Test
    fun archivePathsAreDerivedFromTheId() {
        assertEquals("assets/cover", DocumentKitFormat.assetPath(AssetId.of("cover")))
    }
}
