package io.github.masterplaycoding.documentkit.io

import io.github.masterplaycoding.documentkit.AssetId
import io.github.masterplaycoding.documentkit.DocumentKitFormat
import io.github.masterplaycoding.documentkit.DocumentLimits
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * `docs/format-v1.md` calls itself the normative specification, and until now
 * it was prose next to an unrelated implementation.
 *
 * A specification that nothing checks describes the code at the moment it was
 * written and drifts from it silently afterwards. This reads the document and
 * asserts the build agrees with it — so a default changed in Kotlin and not in
 * the table fails here rather than misleading whoever implements a reader from
 * the spec alone.
 *
 * Deliberately parsing the published table rather than restating its numbers in
 * Kotlin: a copy of the values would only prove the copy matches, which is the
 * thing that was already true.
 */
class SpecificationConformanceTest {

    private lateinit var workspace: File
    private val store = DocumentStore()

    @BeforeTest
    fun setUp() {
        workspace = File.createTempFile("documentkit-spec-", "").apply {
            delete()
            mkdirs()
        }
    }

    @AfterTest
    fun tearDown() {
        workspace.deleteRecursively()
    }

    /**
     * Walks up from the module directory until the repository root is found.
     *
     * The working directory differs between a Gradle run and an IDE run, and a
     * spec test that only works in one of them would be quietly skipped in the
     * other.
     */
    private fun specification(): String {
        var directory: File? = File(".").absoluteFile
        while (directory != null) {
            val candidate = File(directory, "docs/format-v1.md")
            if (candidate.isFile) return candidate.readText()
            directory = directory.parentFile
        }
        throw AssertionError("could not find docs/format-v1.md from ${File(".").absolutePath}")
    }

    /** `| Any single asset | 64 MiB |` -> `"Any single asset" to 64 MiB in bytes`. */
    private fun documentedLimits(): Map<String, Long> {
        val row = Regex("""^\|\s*(.+?)\s*\|\s*([\d,]+)\s*(MiB|KiB)?\s*\|$""")
        val limits = mutableMapOf<String, Long>()

        for (line in specification().lineSequence()) {
            val match = row.find(line.trim()) ?: continue
            val (label, number, unit) = match.destructured
            if (label.startsWith("---") || label == "Limit") continue
            val value = number.replace(",", "").toLong()
            limits[label.trim('`', ' ')] = when (unit) {
                "MiB" -> value * 1024 * 1024
                "KiB" -> value * 1024
                else -> value
            }
        }
        return limits
    }

    @Test
    fun theDocumentedLimitsAreTheOnesTheBuildApplies() {
        val documented = documentedLimits()
        val actual = DocumentLimits.Default

        // If a row is renamed the lookup fails loudly rather than skipping,
        // because a silently absent assertion is the failure mode this whole
        // class exists to prevent.
        val expected = mapOf(
            "Archive file size" to actual.maxArchiveBytes,
            "Total actual uncompressed bytes" to actual.maxTotalUncompressedBytes,
            "Entry count" to actual.maxEntryCount.toLong(),
            "manifest.json" to actual.maxManifestBytes,
            "document.json" to actual.maxDocumentBytes,
            "Any single asset" to actual.maxAssetBytes,
            "JSON nesting depth" to actual.maxJsonDepth.toLong(),
        )

        for ((label, value) in expected) {
            val fromSpec = documented[label]
                ?: throw AssertionError(
                    "docs/format-v1.md has no limits row called '$label'. " +
                        "Rows found: ${documented.keys.sorted()}",
                )
            assertEquals(
                value,
                fromSpec,
                "docs/format-v1.md says $label is $fromSpec, the build applies $value",
            )
        }
    }

    @Test
    fun theDocumentedEntryNamesAreTheOnesTheBuildWrites() = runTest {
        val specification = specification()

        for (entry in listOf(DocumentKitFormat.MANIFEST_ENTRY, DocumentKitFormat.DOCUMENT_ENTRY)) {
            assertTrue(
                specification.contains(entry),
                "docs/format-v1.md never mentions '$entry'",
            )
        }
        assertTrue(specification.contains(DocumentKitFormat.ASSET_PREFIX))
    }

    @Test
    fun aWrittenContainerHasExactlyTheLayoutTheSpecificationDescribes() = runTest {
        val file = File(workspace, "doc.dkit")
        store.save(
            destination = file,
            document = Notebook("Spec"),
            documentId = "doc-1",
            codec = notebookCodec,
            assets = mapOf(AssetId.of("cover") to AssetSource.ofBytes(ByteArray(32))),
        )

        val names = ZipFile(file).use { archive ->
            archive.entries().asSequence().map { it.name }.toList()
        }

        // "A container is a ZIP archive containing exactly these entries."
        assertEquals(
            listOf("manifest.json", "document.json", "assets/cover").sorted(),
            names.sorted(),
            "the archive holds entries the specification does not describe",
        )
    }

    @Test
    fun theManifestCarriesExactlyTheDocumentedFieldNames() = runTest {
        val file = File(workspace, "doc.dkit")
        store.save(
            destination = file,
            document = Notebook("Spec"),
            documentId = "doc-1",
            codec = notebookCodec,
            assets = mapOf(AssetId.of("cover") to AssetSource.ofBytes(ByteArray(32))),
        )

        val manifestJson = ZipFile(file).use { archive ->
            archive.getInputStream(archive.getEntry("manifest.json")).readBytes().decodeToString()
        }

        // The serialised names, not the Kotlin property names. A @SerialName
        // dropped in refactoring renames a field in every file the library has
        // ever written, and only a reader would ever find out.
        for (field in listOf(
            "container_version",
            "application_id",
            "schema_version",
            "document_id",
            "document_length",
            "document_sha256",
            "assets",
        )) {
            assertTrue(
                manifestJson.contains("\"$field\""),
                "manifest.json is missing the documented field '$field': $manifestJson",
            )
            assertTrue(
                specification().contains("`$field`") || specification().contains("\"$field\""),
                "docs/format-v1.md does not document '$field'",
            )
        }
    }

    @Test
    fun theManifestCarriesNoTimestamp() = runTest {
        // "There are deliberately no timestamps." The reason is in the spec:
        // a writer that stamps the time makes two saves of identical content
        // differ. This is what stops one being added without the argument
        // being revisited.
        val file = File(workspace, "doc.dkit")
        store.save(file, Notebook("Spec"), "doc-1", notebookCodec)

        val manifestJson = ZipFile(file).use { archive ->
            archive.getInputStream(archive.getEntry("manifest.json")).readBytes().decodeToString()
        }

        for (suspect in listOf("timestamp", "created", "modified", "saved_at", "time")) {
            assertTrue(
                !manifestJson.contains(suspect),
                "manifest.json contains '$suspect', which the specification rules out: $manifestJson",
            )
        }
    }

    @Test
    fun theContainerVersionTheSpecificationDescribesIsTheOneWritten() = runTest {
        val file = File(workspace, "doc.dkit")
        store.save(file, Notebook("Spec"), "doc-1", notebookCodec)

        store.inspect(file).let { summary ->
            assertEquals(DocumentKitFormat.CONTAINER_VERSION, summary.containerVersion)
        }

        assertTrue(
            specification().contains("\"container_version\": ${DocumentKitFormat.CONTAINER_VERSION}"),
            "the specification's example manifest does not show container_version " +
                "${DocumentKitFormat.CONTAINER_VERSION}",
        )
    }
}
