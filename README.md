# DocumentKit

**Versioned save files, assets, migrations and explicit storage guarantees for Kotlin applications.**

If your application saves a document — a notebook, a diagram, a project file —
you eventually need all of this: a container that holds structured data and
binary assets, integrity checks, a way to open last year's file, and a save that
does not destroy the previous version when it fails. DocumentKit is that layer,
extracted from a real editor and made general.

> The library and its format are implemented and covered by tests on Linux,
> Windows and macOS, with an independent consumer build per platform proving
> the published artifacts resolve. `documentkit-android` is verified by an
> Android consumer build; it has no instrumented tests yet. Anything not
> documented below is not built; see [Roadmap](#roadmap).

---

## Getting started

**Which module?** Depend on one; the others come with it.

| Depend on | When |
|---|---|
| `documentkit-io` | Desktop or server JVM. Brings in `documentkit-core`. |
| `documentkit-android` | Android. Brings in both of the above. |
| `documentkit-core` | Only if you are writing your own I/O layer and want the format types, codec and migrations alone. |

Build it once into your local Maven repository:

```bash
git clone https://github.com/MasterplaYCoding/DocumentKit.git
cd DocumentKit && ./gradlew publishToMavenLocal
```

`settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        mavenLocal()
        mavenCentral()
        google()          // only if you are building for Android
    }
}
```

`build.gradle.kts`:

```kotlin
plugins {
    kotlin("jvm") version "2.2.0"
    // Required. Your document model is @Serializable, and a library cannot
    // supply a compiler plugin on your behalf.
    kotlin("plugin.serialization") version "2.2.0"
}

dependencies {
    implementation("io.github.masterplaycoding.documentkit:documentkit-io:0.1.0-SNAPSHOT")
}
```

You do **not** need to declare `kotlinx-coroutines` or
`kotlinx-serialization-json` yourself — both arrive transitively, and
[`consumer-check/`](consumer-check) is a separate build that fails if they ever
stop doing so.

**Requirements:** JDK 17, Kotlin 2.2.0, Android API 24+ (compiled against
SDK 36).

<details>
<summary>Why <code>mavenLocal()</code> and not <code>mavenCentral()</code>?</summary>

Because it is not on Maven Central yet, and saying otherwise would be the kind
of claim this project spends a lot of words avoiding.

The publishing pipeline is built and tested — see [RELEASING.md](RELEASING.md)
and [`.github/workflows/release.yml`](.github/workflows/release.yml), which
gates a publish on the same coordinate verification and consumer builds CI
runs. It is deliberately held until the `0.2` API settles, since a Central
version is permanently immutable.

The group id `io.github.masterplaycoding.documentkit` is already the one
Central will verify, so the coordinates above will not change when it lands —
only the repository line.

</details>

> **Every I/O entry point is a `suspend` function** and runs on
> `Dispatchers.IO`. The snippets below use `runBlocking` to stay short; in an
> application, call them from whatever scope you already have.

---

## Saving a document

```kotlin
import io.github.masterplaycoding.documentkit.AssetId
import io.github.masterplaycoding.documentkit.DocumentCodec
import io.github.masterplaycoding.documentkit.io.AssetSource
import io.github.masterplaycoding.documentkit.io.DocumentStore
import io.github.masterplaycoding.documentkit.io.SaveReceipt
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable

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
    validate = { notebook ->
        if (notebook.title.isBlank()) "a notebook needs a title" else null
    },
)

fun main() = runBlocking {
    val store = DocumentStore()
    val directory = File(System.getProperty("user.home"), "Documents")
    val coverImage = File(directory, "cover.png")

    val notebook = Notebook(
        title = "Field notes",
        notes = listOf(Note("first"), Note("with a picture", imageAssetId = "cover")),
    )

    val receipt = store.save(
        destination = File(directory, "field-notes.dkit"),
        document = notebook,
        documentId = "9c1f-4a2e-…",          // yours; stable across saves
        codec = codec,
        assets = mapOf(AssetId.of("cover") to AssetSource.ofFile(coverImage)),
    )

    check(receipt is SaveReceipt.AtomicReplace)
}
```

## Opening one

```kotlin
val documentFile = File(directory, "field-notes.dkit")

store.open(documentFile, codec).use { opened ->
    println(opened.document.title)
    println(opened.migrationsApplied)   // e.g. ["notes-become-objects"]

    opened.copyAssetTo(AssetId.of("cover"), File(directory, "extracted-cover.png"))
}
```

`use { }` matters: the handle owns the open archive, and on Android any staging
copy, until it is closed.

## Opening an older one

Add a migration per schema version you have ever shipped, and raise
`schemaVersion` to match:

```kotlin
import io.github.masterplaycoding.documentkit.documentMigration
import kotlinx.serialization.json.*

// Version 1 stored notes as bare strings; version 2 stores objects, so a note
// can carry an image.
val notesBecomeObjects = documentMigration("notes-become-objects", fromVersion = 1) { document ->
    buildJsonObject {
        put("title", document["title"] ?: JsonPrimitive("Untitled"))
        putJsonArray("notes") {
            document["notes"]?.jsonArray?.forEach { note ->
                add(buildJsonObject { put("text", note.jsonPrimitive.content) })
            }
        }
    }
}

val codec = DocumentCodec(
    applicationId = "example.notebook",
    schemaVersion = 2,
    serializer = Notebook.serializer(),
    migrations = listOf(notesBecomeObjects),
)
```

Opening a version-1 file migrates it **in memory**. The file on disk is not
rewritten — upgrading a user's document because they looked at it is not a
decision a library gets to make. Saving is what writes.

## Opening a damaged one

```kotlin
import io.github.masterplaycoding.documentkit.DocumentError
import io.github.masterplaycoding.documentkit.DocumentException

val message = try {
    store.open(suspiciousFile, codec).use { opened -> opened.document.title }
} catch (failure: DocumentException) {
    when (val error = failure.error) {
        is DocumentError.IntegrityMismatch -> "'${error.entry}' does not match its digest"
        is DocumentError.UnsupportedSchema -> "written by a newer version of this app"
        is DocumentError.MissingMigration  -> "too old for this build to read"
        else -> error.detail
    }
}
```

Errors are structured and name the entry or migration step involved. They do
not carry document contents: an error message ends up in a log or a bug report,
and the user's document is theirs. That is enforced by tests that plant a
sentinel value in a document and assert it appears in no error, message or
stack trace.

## Android

```kotlin
import io.github.masterplaycoding.documentkit.android.DocumentTransfer

val transfer = DocumentTransfer(context)

// A Uri you already obtained from the Storage Access Framework. This library
// does not request permissions or launch pickers — those are your UI.
transfer.import(uri, codec).use { opened -> render(opened.document) }

val receipt = transfer.exportCopy(uri, notebook, documentId, codec, assets)
```

Export returns `ProviderManagedExport`, not `AtomicReplace`, and the difference
is real — see [the write-up](docs/decisions/002-atomic-replace-vs-provider-export.md).

---

## Inspecting a file from the command line

```bash
./gradlew :documentkit-cli:installDist
```

```bash
documentkit inspect field-notes.dkit
```

```
field-notes.dkit
  container version   1
  application         example.notebook
  schema version      1
  document id         consumer-check-1
  archive size        1 KiB
  entries             3
  document.json       183 B (declared)
  assets              1, 8 KiB declared
    cover  8 KiB

Sizes above are declared, not verified. Run `validate` to check them.
```

`validate` streams every entry and checks actual lengths and SHA-256 digests:

```
damaged.dkit — example.notebook schema 1
  ✓ document.json
  ✗ IntegrityMismatch: entry 'assets/cover' expected 8192 bytes but found 8318 bytes

invalid — 1 problem
checked: container structure and integrity; application schema not checked
```

Exit codes are `0` valid, `1` invalid document, `2` bad invocation — kept
distinct because a build that cannot tell "your document is corrupt" from "you
typed the wrong flag" teaches people to ignore both. `--json` gives CI a
machine-readable form.

Both commands work on a container belonging to an application this build knows
nothing about, and neither claims more than it checked: without your codec, the
CLI verifies the container and says so in as many words.

## Guarantees, each with its limit

| What DocumentKit guarantees | Where that stops |
|---|---|
| A local save replaces the destination **atomically**, or fails with `AtomicReplaceUnsupported`. There is no silent fallback to a copy. | Atomic *visibility* on that filesystem. Not power-loss durability, not directory metadata (Java has no portable directory fsync), and nothing about network filesystems. |
| An interrupted save leaves the previous document byte-for-byte unchanged. | Until the atomic move. After it commits, the save succeeded, even if cancellation arrived during the commit. |
| Opening verifies every declared byte against its length and SHA-256 before returning. | Digests detect corruption and mismatched content. They do **not** authenticate an author: whoever rewrites content can rewrite the manifest. |
| Limits bound bytes actually streamed, so a decompression bomb costs the limit rather than the bomb. | Limits are configurable, and a writer applies the same ones as its reader — so raising them on one side alone produces files that will not reopen. |
| Assets stream in both directions; nothing buffers a whole archive. | An `AssetSource` is read twice per save (measure, then write). It must return a fresh stream each time. |
| An opened document owns its resources and releases them on `close`. | One handle is not safe for concurrent use. Independent handles on the same file are fine. |
| Migrations run through a complete, gap-free chain, validated when the codec is built. | Migrations transform JSON, one version per step, and never touch assets. Asset conversion is the application's job. |
| Android export builds and verifies the archive privately before opening the destination. | The provider owns the destination. A `ProviderManagedExport` is a copy, not a crash-safe overwrite; an interruption mid-copy can leave partial content, and the error says so. |

## Modules

| Module | Contents |
|---|---|
| `documentkit-core` | Format types, codec, migrations, errors, limits. No Java, no UI toolkit. |
| `documentkit-io` | Shared JVM/Android archive implementation and local-file save. |
| `documentkit-android` | Storage Access Framework import and export. |
| `documentkit-cli` | `inspect` and `validate` for any container. |

Not published, and not part of the supported surface:

| Sample | What it is |
|---|---|
| [`samples/lantr-import`](samples/lantr-import) | Converts a Lantr `.ltrn` presentation into a DocumentKit container, and reports everything it did not carry across. |

The archive implementation lives in one intermediate source set compiled for
both JVM and Android. Two copies is how they drift.

Nothing depends on Compose. A codec is constructible from a plain serialisable
model, which is what lets the same document type serve a desktop app, an
Android app, a CLI and a test.

## Roadmap

| Milestone | Contents | State |
|---|---|---|
| `0.1` | Container format v1, codec, migration chain, JVM/Android archives, streamed assets, limits, validation, atomic local replacement, SAF import/export | **implemented** |
| `0.2` | Inspect/validate CLI, integrity reporting, Lantr legacy importer | **implemented**, release pending |
| `0.3` | Android instrumented tests at API 24 and 36, expanded malformed-input corpus, benchmarks | planned |
| `1.0` | Stable API and format, compatibility policy, fuzz regressions | planned |

## Documentation

- [Container format, version 1](docs/format-v1.md) — the normative specification.
- [Troubleshooting](docs/troubleshooting.md) — what each structured error means and what to do.
- [Extracting a reusable persistence layer from Lantr](docs/decisions/001-extraction-from-lantr.md)
- [Atomic replacement versus Android provider export](docs/decisions/002-atomic-replace-vs-provider-export.md)
- [Validating archive contents without trusting size metadata](docs/decisions/003-untrusted-size-metadata.md)
- [Source provenance](PROVENANCE.md) — what came from Lantr, and what was replaced.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Parser and archive-handling security
reports go through [SECURITY.md](SECURITY.md).

## Licence

MIT. See [LICENSE](LICENSE); Lantr's notice is retained in
[PROVENANCE.md](PROVENANCE.md).
