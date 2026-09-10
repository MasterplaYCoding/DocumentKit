package io.github.masterplaycoding.documentkit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every rejection [Manifest.validate] can make, exercised directly.
 *
 * These checks run against a manifest someone else wrote, before a single byte
 * of the archive is trusted, and every one of them was previously unverified -
 * the function had no test referencing it at all. A validation routine nothing
 * exercises is indistinguishable from a validation routine that returns an
 * empty list, and both pass every other test in the repository.
 */
class ManifestValidationTest {

    private val digest = "a".repeat(64)

    private fun manifest(
        containerVersion: Int = 1,
        applicationId: String = "example.notebook",
        schemaVersion: Int = 1,
        documentId: String = "doc-1",
        documentLength: Long = 10,
        documentSha256: String = digest,
        assets: List<AssetEntry> = emptyList(),
    ) = Manifest(
        containerVersion = containerVersion,
        applicationId = applicationId,
        schemaVersion = schemaVersion,
        documentId = documentId,
        documentLength = documentLength,
        documentSha256 = documentSha256,
        assets = assets,
    )

    private fun asset(
        id: String = "cover",
        length: Long = 1,
        sha256: String = "b".repeat(64),
    ) = AssetEntry(id = AssetId.of(id), length = length, sha256 = sha256)

    /** The single [DocumentError.InvalidManifest] reason, or a failed test. */
    private fun soleReason(errors: List<DocumentError>): String {
        assertEquals(1, errors.size, "expected exactly one problem, got: $errors")
        return assertIs<DocumentError.InvalidManifest>(errors.single()).reason
    }

    @Test
    fun acceptsAWellFormedManifest() {
        assertEquals(emptyList(), manifest().validate())
    }

    @Test
    fun acceptsAManifestWithNoAssets() {
        assertEquals(emptyList(), manifest(assets = emptyList()).validate())
    }

    // --- versions ----------------------------------------------------------

    @Test
    fun rejectsANonPositiveContainerVersion() {
        for (version in listOf(0, -1, Int.MIN_VALUE)) {
            val reason = soleReason(manifest(containerVersion = version).validate())
            assertTrue(
                reason.contains("container_version"),
                "version $version reported '$reason'",
            )
        }
    }

    @Test
    fun reportsAFutureContainerVersionAsUnsupportedRatherThanInvalid() {
        // The distinction is the whole point of having a container version: a
        // file from a newer writer is not damaged, and telling the user to
        // upgrade is a different message from telling them the file is broken.
        val errors = manifest(containerVersion = DocumentKitFormat.CONTAINER_VERSION + 1).validate()

        val error = assertIs<DocumentError.UnsupportedContainer>(errors.single())
        assertEquals(DocumentKitFormat.CONTAINER_VERSION + 1, error.found)
        assertEquals(DocumentKitFormat.CONTAINER_VERSION, error.supported)
    }

    @Test
    fun rejectsANonPositiveSchemaVersion() {
        for (version in listOf(0, -1)) {
            assertTrue(soleReason(manifest(schemaVersion = version).validate()).contains("schema_version"))
        }
    }

    // --- identity ----------------------------------------------------------

    @Test
    fun rejectsABlankApplicationId() {
        // Blank, not merely empty: a whitespace id would otherwise pass and
        // then fail to match any application on open.
        for (id in listOf("", " ", "\t", "\n", "   ")) {
            assertTrue(
                soleReason(manifest(applicationId = id).validate()).contains("application_id"),
                "should have rejected application_id '${id.replace("\n", "\\n")}'",
            )
        }
    }

    @Test
    fun rejectsABlankDocumentId() {
        for (id in listOf("", " ", "\t")) {
            assertTrue(soleReason(manifest(documentId = id).validate()).contains("document_id"))
        }
    }

    // --- lengths and digests -----------------------------------------------

    @Test
    fun rejectsANegativeDocumentLength() {
        assertTrue(soleReason(manifest(documentLength = -1).validate()).contains("document_length"))
        assertTrue(
            soleReason(manifest(documentLength = Long.MIN_VALUE).validate())
                .contains("document_length"),
        )
    }

    @Test
    fun acceptsAZeroDocumentLength() {
        // An empty document is a legal document. Only negative is nonsense.
        assertEquals(emptyList(), manifest(documentLength = 0).validate())
    }

    @Test
    fun rejectsADocumentDigestThatIsNotSha256() {
        val bad = mapOf(
            "empty" to "",
            "too short" to "a".repeat(63),
            "too long" to "a".repeat(65),
            "not hex" to "z".repeat(64),
            "hex with a space" to "a".repeat(63) + " ",
            "sha1 length" to "a".repeat(40),
            "prefixed" to "sha256:" + "a".repeat(57),
        )

        for ((label, value) in bad) {
            assertTrue(
                soleReason(manifest(documentSha256 = value).validate()).contains("document_sha256"),
                "should have rejected a $label digest",
            )
        }
    }

    @Test
    fun rejectsAnUppercaseDigest() {
        // Deliberate, and worth pinning: digests are compared as strings
        // elsewhere, so accepting both cases would make two spellings of the
        // same digest unequal. One canonical form or none.
        assertTrue(
            soleReason(manifest(documentSha256 = "A".repeat(64)).validate())
                .contains("document_sha256"),
        )
    }

    // --- the asset index ---------------------------------------------------

    @Test
    fun rejectsAnAssetIndexedTwice() {
        val errors = manifest(assets = listOf(asset("cover"), asset("cover"))).validate()

        assertTrue(soleReason(errors).contains("indexed twice"))
    }

    @Test
    fun acceptsDistinctAssetsThatShareEverythingElse() {
        // Same bytes, same digest, different ids. Ids are not derived from
        // content, so this is two assets and must not read as a duplicate.
        val errors = manifest(
            assets = listOf(asset("cover", 4, "c".repeat(64)), asset("thumb", 4, "c".repeat(64))),
        ).validate()

        assertEquals(emptyList(), errors)
    }

    @Test
    fun rejectsANegativeAssetLength() {
        val reason = soleReason(manifest(assets = listOf(asset("cover", length = -1))).validate())

        assertTrue(reason.contains("cover"), "the reason should name the asset: '$reason'")
        assertTrue(reason.contains("negative"))
    }

    @Test
    fun rejectsAnAssetDigestThatIsNotSha256() {
        val reason = soleReason(manifest(assets = listOf(asset(sha256 = "nope"))).validate())

        assertTrue(reason.contains("cover"))
        assertTrue(reason.contains("SHA-256"))
    }

    // --- arithmetic on hostile input ---------------------------------------

    @Test
    fun rejectsDeclaredLengthsThatOverflow() {
        // The check this exercises exists because a naive total would wrap to
        // a small positive number and sail past any size limit expressed as
        // "total must be under N". Two assets are enough to cross Long.
        val errors = manifest(
            documentLength = Long.MAX_VALUE / 2,
            assets = listOf(
                asset("a", length = Long.MAX_VALUE / 2),
                asset("b", length = Long.MAX_VALUE / 2),
            ),
        ).validate()

        assertTrue(
            errors.filterIsInstance<DocumentError.InvalidManifest>()
                .any { it.reason.contains("overflow") },
            "expected an overflow report, got: $errors",
        )
    }

    @Test
    fun reportsOverflowOnceRatherThanPerAsset() {
        // The loop breaks at the first overflow. Ten assets past the boundary
        // is still one problem with the manifest, not ten.
        val errors = manifest(
            documentLength = Long.MAX_VALUE - 1,
            assets = List(10) { index -> asset("a$index", length = Long.MAX_VALUE / 4) },
        ).validate()

        assertEquals(
            1,
            errors.count { it is DocumentError.InvalidManifest && it.reason.contains("overflow") },
        )
    }

    @Test
    fun acceptsLengthsThatReachTheBoundaryWithoutCrossingIt() {
        // Long.MAX_VALUE exactly is representable, so it is not an overflow.
        // Off-by-one here would reject the largest legal manifest.
        val errors = manifest(
            documentLength = Long.MAX_VALUE - 1,
            assets = listOf(asset("a", length = 1)),
        ).validate()

        assertEquals(emptyList(), errors)
    }

    // --- the advisory total ------------------------------------------------

    @Test
    fun sumsDeclaredLengths() {
        val manifest = manifest(
            documentLength = 100,
            assets = listOf(asset("a", length = 20), asset("b", length = 3)),
        )

        assertEquals(123, manifest.declaredTotalLength())
    }

    @Test
    fun saturatesRatherThanWrappingOnAHostileTotal() {
        // The failure this prevents: a wrapped sum is a small positive number,
        // which satisfies any check phrased as "the declared total is under N"
        // - the one thing an advisory total is used for.
        val manifest = manifest(
            documentLength = Long.MAX_VALUE,
            assets = listOf(asset("a", length = Long.MAX_VALUE)),
        )

        assertEquals(Long.MAX_VALUE, manifest.declaredTotalLength())
    }

    @Test
    fun doesNotLetANegativeLengthShrinkTheTotal() {
        val manifest = manifest(
            documentLength = 100,
            assets = listOf(asset("a", length = -1000)),
        )

        assertEquals(100, manifest.declaredTotalLength())
    }

    // --- reporting ---------------------------------------------------------

    @Test
    fun reportsEveryProblemRatherThanOnlyTheFirst() {
        // The documented contract: a thoroughly broken file should not need
        // one run per defect to diagnose.
        val errors = manifest(
            applicationId = "",
            schemaVersion = 0,
            documentId = " ",
            documentLength = -5,
            documentSha256 = "short",
            assets = listOf(asset("cover", length = -1, sha256 = "also short")),
        ).validate()

        val reasons = errors.filterIsInstance<DocumentError.InvalidManifest>().map { it.reason }
        for (field in listOf("application_id", "schema_version", "document_id", "document_length", "document_sha256")) {
            assertTrue(reasons.any { it.contains(field) }, "no problem reported for $field: $reasons")
        }
        assertTrue(reasons.any { it.contains("negative") }, "asset length not reported: $reasons")
        assertTrue(reasons.count { it.contains("cover") } >= 2, "both asset problems: $reasons")
    }

    @Test
    fun everyReportedProblemCarriesAReadableDescription() {
        val errors = manifest(documentSha256 = "", documentLength = -1).validate()

        if (errors.isEmpty()) fail("expected problems to report")
        for (error in errors) {
            val described = error.toString()
            assertTrue(described.isNotBlank(), "an error with no description is unactionable")
        }
    }
}
