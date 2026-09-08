import io.github.masterplaycoding.documentkit.AssetId
import io.github.masterplaycoding.documentkit.DocumentCodec
import io.github.masterplaycoding.documentkit.DocumentError
import io.github.masterplaycoding.documentkit.DocumentException
import io.github.masterplaycoding.documentkit.io.AssetSource
import io.github.masterplaycoding.documentkit.io.DocumentStore
import io.github.masterplaycoding.documentkit.io.SaveReceipt
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable

/**
 * The README's example, run against resolved artifacts.
 *
 * Exits non-zero if anything the README claims is not true of the published
 * library.
 */

@Serializable
data class Notebook(val title: String, val notes: List<Note> = emptyList())

@Serializable
data class Note(val text: String, val imageAssetId: String? = null)

val codec = DocumentCodec(
    applicationId = "example.notebook",
    schemaVersion = 1,
    serializer = Notebook.serializer(),
    referencedAssets = { notebook ->
        notebook.notes.mapNotNull { note -> note.imageAssetId?.let(AssetId::of) }.toSet()
    },
    validate = { notebook -> if (notebook.title.isBlank()) "a notebook needs a title" else null },
)

fun main() = runBlocking {
    val workspace = File("build/consumer-check").apply { mkdirs() }
    val file = File(workspace, "field-notes.dkit")
    val store = DocumentStore()

    val cover = ByteArray(8192) { (it % 251).toByte() }
    val notebook = Notebook(
        title = "Field notes",
        notes = listOf(Note("first"), Note("with a picture", imageAssetId = "cover")),
    )

    val receipt = store.save(
        destination = file,
        document = notebook,
        documentId = "consumer-check-1",
        codec = codec,
        assets = mapOf(AssetId.of("cover") to AssetSource.ofBytes(cover)),
    )
    check(receipt is SaveReceipt.AtomicReplace) { "expected an atomic replacement, got $receipt" }

    store.open(file, codec).use { opened ->
        check(opened.document == notebook) { "round trip changed the model" }
        check(opened.readAsset(AssetId.of("cover")).contentEquals(cover)) { "asset bytes differ" }
    }

    // Damage the archive and confirm the failure is structured, not a crash.
    val damaged = File(workspace, "damaged.dkit")
    file.copyTo(damaged, overwrite = true)
    damaged.writeBytes(damaged.readBytes().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() })

    val failure = runCatching { store.open(damaged, codec).use { } }.exceptionOrNull()
    check(failure is DocumentException) { "a damaged archive should fail with DocumentException" }
    check(failure.error is DocumentError) { "the failure should carry a structured error" }

    // The previous document must survive a failed save.
    val before = file.readBytes()
    runCatching { store.save(file, Notebook(title = " "), "consumer-check-1", codec) }
    check(file.readBytes().contentEquals(before)) { "a failed save modified the destination" }

    println("consumer check passed: saved, reopened, rejected damage, preserved on failure")
}
