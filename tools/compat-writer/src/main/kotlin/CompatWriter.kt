import io.github.masterplaycoding.documentkit.AssetId
import io.github.masterplaycoding.documentkit.DocumentCodec
import io.github.masterplaycoding.documentkit.io.AssetSource
import io.github.masterplaycoding.documentkit.io.DocumentStore
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable

// The same model and codec identity as documentkit-io's test Notebook, so the
// test suite can open what this writes with its own codec. Changing any of it
// changes every file in the corpus.
@Serializable
data class Notebook(val title: String, val notes: List<Note> = emptyList())

@Serializable
data class Note(val text: String, val imageAssetId: String? = null)

val codec = DocumentCodec(
    applicationId = "example.notebook",
    schemaVersion = 2,
    serializer = Notebook.serializer(),
    referencedAssets = { notebook ->
        notebook.notes.mapNotNull { note -> note.imageAssetId?.let(AssetId::of) }.toSet()
    },
)

val document = Notebook(
    title = "Compatibility corpus",
    notes = listOf(Note("plain"), Note("with an image", imageAssetId = "cover"), Note("ünïcødé ✓")),
)

val cover = ByteArray(1000) { (it * 31 % 251).toByte() }

fun main(args: Array<String>) = runBlocking {
    val output = File(args.single())
    output.parentFile?.mkdirs()
    DocumentStore().save(output, document, "compat-1", codec, mapOf(AssetId.of("cover") to AssetSource.ofBytes(cover)))
    println("wrote ${output.path}")
}
