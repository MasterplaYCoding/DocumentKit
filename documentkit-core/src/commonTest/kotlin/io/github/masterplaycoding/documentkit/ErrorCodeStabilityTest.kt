package io.github.masterplaycoding.documentkit

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `DocumentError.code` is documented as "stable, machine-readable, safe to
 * branch on" - an application switches on it, logs it, maps it to a message
 * in its own language. That makes every code string part of the public
 * contract, and a rename a breaking change the API dump cannot see: the dump
 * records that `getCode()` exists, not what it returns.
 *
 * Two mechanisms, one for each way the contract can break:
 *
 * - [recorded] is an exhaustive `when` with no `else`. Adding a subtype
 *   stops this file compiling until someone writes down its code here, which
 *   is the moment to decide that it is stable.
 * - [everyErrorKeepsItsCode] builds one of each and compares, so renaming a
 *   code fails - on purpose, since that belongs in a major version.
 */
class ErrorCodeStabilityTest {

    private fun recorded(error: DocumentError): String = when (error) {
        is DocumentError.UnsupportedContainer -> "UnsupportedContainer"
        is DocumentError.WrongApplication -> "WrongApplication"
        is DocumentError.UnsupportedSchema -> "UnsupportedSchema"
        is DocumentError.MissingMigration -> "MissingMigration"
        is DocumentError.MigrationFailed -> "MigrationFailed"
        is DocumentError.InvalidManifest -> "InvalidManifest"
        is DocumentError.MissingEntry -> "MissingEntry"
        is DocumentError.DuplicateEntry -> "DuplicateEntry"
        is DocumentError.InvalidEntry -> "InvalidEntry"
        is DocumentError.LimitExceeded -> "LimitExceeded"
        is DocumentError.InvalidJson -> "InvalidJson"
        is DocumentError.IntegrityMismatch -> "IntegrityMismatch"
        is DocumentError.MissingReferencedAsset -> "MissingReferencedAsset"
        is DocumentError.ApplicationValidationFailed -> "ApplicationValidationFailed"
        is DocumentError.AtomicReplaceUnsupported -> "AtomicReplaceUnsupported"
        is DocumentError.IoFailure -> "IoFailure"
    }

    private val oneOfEach: List<DocumentError> = listOf(
        DocumentError.UnsupportedContainer(2, 1),
        DocumentError.WrongApplication("a", "b"),
        DocumentError.UnsupportedSchema(3, 2),
        DocumentError.MissingMigration(1, 2),
        DocumentError.MigrationFailed("step", 1, 2, "reason"),
        DocumentError.InvalidManifest("reason"),
        DocumentError.MissingEntry("entry"),
        DocumentError.DuplicateEntry("entry"),
        DocumentError.InvalidEntry("entry", "reason"),
        DocumentError.LimitExceeded("limit", 1),
        DocumentError.InvalidJson("entry", "reason"),
        DocumentError.IntegrityMismatch("entry", "a", "b"),
        DocumentError.MissingReferencedAsset("asset"),
        DocumentError.ApplicationValidationFailed("reason"),
        DocumentError.AtomicReplaceUnsupported("destination", "reason"),
        DocumentError.IoFailure("operation", "reason"),
    )

    @Test
    fun everyErrorKeepsItsCode() {
        for (error in oneOfEach) {
            assertEquals(recorded(error), error.code, "the code of ${error::class.simpleName}")
        }
    }

    @Test
    fun oneOfEachReallyIsOneOfEach() {
        // The when above is exhaustive by the compiler's check; this list is
        // not, so make sure it did not skip a subtype.
        assertEquals(
            oneOfEach.size,
            oneOfEach.map { it::class }.toSet().size,
            "a subtype appears twice",
        )
        assertEquals(16, oneOfEach.size, "a subtype is missing from oneOfEach")
    }
}
