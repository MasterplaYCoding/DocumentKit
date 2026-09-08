# Extracting a reusable persistence layer from Lantr

DocumentKit did not start as a library. It started as `LtrnArchiveService`, two
files of roughly 1,500 lines inside Lantr, a Kotlin Multiplatform presentation
editor — one copy for desktop, one for Android.

That origin is worth being specific about, because "extracted from a real
application" is only a useful claim if you can say what changed on the way out.

## What the original did well

The shape was already right. A ZIP container, a JSON document, binary assets in
a subdirectory, bounded reads, and entry-name validation that rejected `..` and
backslashes. Those decisions survived the extraction intact and are still the
spine of the format.

## What could not survive

### It knew about presentations

`loadPresentation` returned a `Presentation`, built from `Slide`, `Theme`,
`Rhetoric`, `Arc` and a dozen other application types. Roughly two thirds of the
file was parsing those types, not handling archives.

The extraction inverted the dependency: `DocumentCodec<T>` describes an
application's model to the library instead of the library knowing the model.
Everything in `documentkit-core` compiles with no Java, no Compose and no
application types at all.

### It opened the destination before it was ready

```kotlin
ZipOutputStream(file.outputStream().buffered()).use { output ->
    // …build the whole archive, straight into the user's file…
}
```

Opening a file for writing truncates it. From that instant until the last entry
is written, the user's saved document is gone, and anything that fails in
between — a missing image, a full disk, the process dying — leaves them with a
partial archive that will not open.

Saving now builds the complete archive in a sibling temporary file, verifies it
by *opening it as a reader would*, forces it to storage, and only then moves it
over the destination atomically. The sibling matters: a cross-filesystem move
cannot be atomic, so the staging file has to live beside the destination rather
than in the system temp directory.

### It trusted the archive about the archive

```kotlin
val declaredSize = entries.sumOf { entry -> entry.size.coerceAtLeast(0L) }
require(declaredSize <= MAX_ARCHIVE_BYTES)
```

`entry.size` is a number written *inside the file being validated*. A crafted
archive can declare a kilobyte and expand to gigabytes. This is a
[decompression bomb](003-untrusted-size-metadata.md), and it is the single most
consequential thing the extraction changed.

### It loaded whole assets into memory

`imageFile.readBytes()` on save, `readNBytes(maxBytes + 1)` on load. Fine for
a few hundred kilobytes of slide images; not fine as a general contract. Assets
now stream through a 64 KiB buffer in both directions, and `AssetSource` is a
stream factory rather than a `ByteArray`.

### It flattened asset paths

```kotlin
val targetFile = File(tempDirectory, entry.name.substringAfterLast('/'))
```

`assets/a/cover.png` and `assets/b/cover.png` both become `cover.png`. One
silently overwrites the other.

Asset ids are now a validated value class, and archive paths are *derived* from
ids rather than read from the archive. There is no path to flatten, because the
reader never takes one from the container.

### It invented data when it did not like what it found

```kotlin
private fun safeUuid(value: String): UUID =
    try { UUID.fromString(value) } catch (_: Exception) { UUID.randomUUID() }
```

A malformed document id becomes a fresh one. The document opens, nothing is
reported, and it is now a *different document* than the one on disk — with
consequences for anything keyed on that id. `parseDate` did the same with the
current time.

Malformed identifiers are now `InvalidManifest`. There is no "safe" fallback,
because inventing an identity is not safer than saying you could not read one.

### It dropped what it did not recognise

`ignoreUnknownKeys = true`, plus `mapNotNull` over blocks, plus
`if (imageFile.exists())` before writing an image. Three separate ways for
content to vanish without an error.

`ignoreUnknownKeys` is now off, unknown archive entries are rejected rather than
ignored, and a referenced asset that is missing fails the save.

### It left temporary files to the JVM

`Files.createTempDirectory(...).apply { deleteOnExit() }`, with every extracted
image also registered for deletion at exit. Files that outlive the document,
are owned by nobody, and accumulate until the process ends — which on Android
is whenever the system decides.

Nothing is extracted on open. Assets stream from the verified archive to a
destination the caller chooses, and an opened document owns and deletes its own
staging file on `close`.

## What the extraction is worth

The honest summary: the format design survived, and roughly seven correctness
behaviours did not. None of those seven were sloppiness — they were reasonable
choices for an application that controls its own inputs, and every one of them
becomes a defect the moment the code is handed an arbitrary file from an
arbitrary source.

That is the whole difference between application code and a library, and it is
mostly not about API design.

## What is deliberately still outstanding

- Lantr itself has not been migrated onto DocumentKit. That is a larger piece
  of work than the library, and the samples plus the planned legacy importer
  provide consumer evidence without it.
- The `0.3` importer will read the `.ltrn` layout for supported fixture content
  only. It will never write `.ltrn`, and there is no bidirectional
  compatibility promise.

See [PROVENANCE.md](../../PROVENANCE.md) for the full inventory, pinned to the
Lantr revision the extraction was made from.
