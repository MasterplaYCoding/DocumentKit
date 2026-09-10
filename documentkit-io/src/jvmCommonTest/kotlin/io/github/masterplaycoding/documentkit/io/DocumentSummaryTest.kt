package io.github.masterplaycoding.documentkit.io

import io.github.masterplaycoding.documentkit.AssetEntry
import io.github.masterplaycoding.documentkit.AssetId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [DocumentSummary] is public and constructible, so its arithmetic has to hold
 * for values that never came through [DocumentStore.inspect].
 *
 * Reaching it that way is the ordinary route and it is safe: inspect validates
 * the manifest before summarising, so lengths that cannot add up are refused
 * rather than totalled. That is a property of the reader, not of this class,
 * and a caller who builds a summary from numbers of their own gets none of it.
 */
class DocumentSummaryTest {

    private fun summary(documentLength: Long, vararg assetLengths: Long) = DocumentSummary(
        containerVersion = 1,
        applicationId = "example.notebook",
        schemaVersion = 1,
        documentId = "doc-1",
        documentLength = documentLength,
        assets = assetLengths.mapIndexed { index, length ->
            AssetEntry(id = AssetId.of("a$index"), length = length, sha256 = "c".repeat(64))
        },
        entryCount = 2 + assetLengths.size,
        archiveBytes = 1024,
    )

    @Test
    fun sumsDeclaredContent() {
        assertEquals(160, summary(100, 40, 20).declaredContentBytes)
    }

    @Test
    fun saturatesRatherThanWrapping() {
        // A wrapped total is a small - often negative - number, and the only
        // thing an advisory total gets used for is a comparison against a
        // limit, which such a number passes.
        val total = summary(Long.MAX_VALUE, Long.MAX_VALUE).declaredContentBytes

        assertEquals(Long.MAX_VALUE, total)
        assertTrue(total > 0)
    }

    @Test
    fun ignoresNegativeLengthsRatherThanSubtractingThem() {
        assertEquals(100, summary(100, -1000).declaredContentBytes)
    }

    @Test
    fun treatsANegativeDocumentLengthAsNothingRatherThanADebt() {
        assertEquals(40, summary(-50, 40).declaredContentBytes)
    }
}
