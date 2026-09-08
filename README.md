# DocumentKit

**Versioned save files, assets, migrations and explicit storage guarantees for Kotlin applications.**

If your application saves a document — a notebook, a diagram, a project file —
you eventually need all of this: a container that holds structured data and
binary assets, integrity checks, a way to open last year's file, and a save that
does not destroy the previous version when it fails. DocumentKit is that layer,
extracted from a real editor and made general.

> **Status: pre-release, working toward `0.1`.** Everything below is implemented
> and tested. Anything not below is not built yet; see [Roadmap](#roadmap).

---

## Saving a document

```kotlin
@Serializable
data class Notebook(val title: String, val notes: List<Note> = emptyList())

val codec = DocumentCodec(
    applicationId = "example.notebook",
    schemaVersion = 2,
    serializer = Notebook.serializer(),
    migrations = listOf(notesBecomeObjects),
    referencedAssets = { it.notes.mapNotNull { note -> note.imageAssetId?.let(AssetId::of) }.toSet() },
    validate = { if (it.title.isBlank()) "a notebook needs a title" else null },
)

val store = DocumentStore()

val receipt = store.save(
    destination = File(directory, "field-notes.dkit"),
    document = notebook,
    documentId = "9c1f-…",
    codec = codec,
    assets = mapOf(AssetId.of("cover") to AssetSource.ofFile(coverImage)),
)
// receipt is SaveReceipt.AtomicReplace
```

## Opening one, including an old one

```kotlin
store.open(file, codec).use { opened ->
    println(opened.document.title)
    println(opened.migrationsApplied)   // ["notes-become-objects"] for a v1 file
    opened.copyAssetTo(AssetId.of("cover"), destination)
}
```

Opening a version-1 file migrates it **in memory**. The file on disk is not
rewritten — upgrading a user's document because they looked at it is not a
decision a library gets to make. Saving is what writes.

## Opening a damaged one

```kotlin
try {
    store.open(suspiciousFile, codec).use { /* … */ }
} catch (failure: DocumentException) {
    when (val error = failure.error) {
        is DocumentError.IntegrityMismatch -> "‘${error.entry}’ does not match its digest"
        is DocumentError.UnsupportedSchema -> "written by a newer version of this app"
        is DocumentError.MissingMigration  -> "too old for this build to read"
        else -> error.detail
    }
}
```

Errors are structured and name the entry or migration step involved. They do
not carry document contents: an error message ends up in a log or a bug report,
and the user's document is theirs.

---

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

The archive implementation lives in one intermediate source set compiled for
both JVM and Android. Two copies is how they drift.

## Requirements

- Kotlin 2.2.0, JDK 17 toolchain.
- JVM desktop: Windows, Linux, macOS.
- Android API 24+, compiled against SDK 36.
- `kotlinx.serialization` 1.9.0 and `kotlinx.coroutines` 1.10.2.

Nothing depends on Compose. A codec is constructible from a plain serialisable
model, which is what lets the same document type serve a desktop app, an
Android app, a CLI and a test.

## Roadmap

| Milestone | Contents | State |
|---|---|---|
| `0.1` | Container format v1, codec, migration chain, JVM/Android archives, streamed assets, limits, validation, atomic local replacement, SAF import/export | **implemented** |
| `0.2` | Inspect/validate CLI, integrity reporting, Android instrumented tests at API 24 and 36 | planned |
| `0.3` | Lantr legacy importer, expanded malformed-input corpus, benchmarks | planned |
| `1.0` | Stable API and format, compatibility policy, fuzz regressions, Maven Central publication | planned |

## Documentation

- [Container format, version 1](docs/format-v1.md) — the normative specification.
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
