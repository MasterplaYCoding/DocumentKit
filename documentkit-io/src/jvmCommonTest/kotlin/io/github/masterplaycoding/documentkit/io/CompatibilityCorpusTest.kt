package io.github.masterplaycoding.documentkit.io

import io.github.masterplaycoding.documentkit.AssetId
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * Every build opens what every release wrote.
 *
 * The files in `src/jvmCommonTest/compat-corpus/` were written by the
 * *released* artifacts - fetched from Maven Central by `tools/compat-writer`,
 * never built from this tree - so they record what users' copies of each
 * version actually produced. A change that stopped this build reading one of
 * them would break a real user's saved file, and would pass every other test
 * here, because every other test writes its fixtures with the current code.
 *
 * The check is semantic, as `docs/format-v1.md` defines compatibility: the
 * decoded model, the asset bytes, and the manifest digests. Archive bytes are
 * not compared - ZIP metadata such as entry times is explicitly outside the
 * contract, and each release's file differs from the others in exactly that.
 *
 * When a version is released, add its file: `./gradlew run
 * -PdocumentkitVersion=X.Y.Z --args=<path>` in tools/compat-writer.
 */
class CompatibilityCorpusTest {

    private val store = DocumentStore()

    private val directory: File by lazy {
        var candidate: File? = File(".").absoluteFile
        while (candidate != null) {
            val found = File(candidate, "documentkit-io/src/jvmCommonTest/compat-corpus")
                .takeIf { it.isDirectory }
                ?: File(candidate, "src/jvmCommonTest/compat-corpus").takeIf { it.isDirectory }
            if (found != null) return@lazy found
            candidate = candidate.parentFile
        }
        throw AssertionError("could not find compat-corpus from ${File(".").absolutePath}")
    }

    /** What tools/compat-writer saves. Kept in step with it by hand. */
    private val expected = Notebook(
        title = "Compatibility corpus",
        notes = listOf(Note("plain"), Note("with an image", imageAssetId = "cover"), Note("ünïcødé ✓")),
    )
    private val cover = ByteArray(1000) { (it * 31 % 251).toByte() }

    private val corpus: List<File>
        get() = directory.listFiles { file -> file.name.startsWith("written-by-") }.orEmpty().sortedBy { it.name }

    @Test
    fun theCorpusCoversEveryReleaseSinceTheFormatWasPublished() {
        // A corpus that silently lost files would pass the tests below
        // trivially. 0.2.0 was the first release on Maven Central.
        val versions = corpus.map { it.name.removePrefix("written-by-").removeSuffix(".dkit") }
        for (release in listOf("0.2.0", "0.3.0", "0.4.0", "0.5.0")) {
            assertTrue(release in versions, "no file written by $release in $versions")
        }
    }

    @Test
    fun thisBuildOpensWhatEveryReleaseWrote() = runTest {
        for (file in corpus) {
            store.open(file, notebookCodec).use { opened ->
                assertEquals(expected, opened.document, "the document in ${file.name}")
                assertEquals(emptyList(), opened.migrationsApplied, "${file.name} needed a migration")
                assertContentEquals(cover, opened.readAsset(AssetId.of("cover")), "the asset in ${file.name}")
            }
        }
    }

    @Test
    fun thisBuildWritesTheSameContentEveryReleaseWrote() = runTest {
        // The digests cover the document's encoded bytes and each asset, so
        // equal digests mean this build's writer produces what users' files
        // already contain - an encoding change would show up here before it
        // produced a file an older release could not read.
        val current = File.createTempFile("documentkit-compat-", ".dkit")
        try {
            store.save(
                current,
                expected,
                "compat-1",
                notebookCodec,
                mapOf(AssetId.of("cover") to AssetSource.ofBytes(cover)),
            )
            val written = manifestOf(current)

            for (file in corpus) {
                assertEquals(written, manifestOf(file), "the manifest in ${file.name}")
            }
        } finally {
            current.delete()
        }
    }

    private fun manifestOf(file: File) = ZipFile(file).use { zip ->
        Json.parseToJsonElement(
            zip.getInputStream(zip.getEntry("manifest.json")).readBytes().decodeToString(),
        ).jsonObject
    }
}
