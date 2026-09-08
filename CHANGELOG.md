# Changelog

Notable changes are recorded here, following
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Until `1.0` the
public API may change between minor versions; the container format will not
change without a `container_version` bump and a migration note.

## [Unreleased]

Working toward `0.1`.

### Added

- **Container format version 1**, specified normatively in
  [docs/format-v1.md](docs/format-v1.md).
- `documentkit-core`: `DocumentCodec`, `DocumentMigration`, `MigrationChain`,
  `AssetId`, `Manifest`, `DocumentLimits`, `DocumentError`, and a
  non-recursive JSON depth pre-scan. No Java and no UI-toolkit dependencies.
- `documentkit-io`: JVM and Android archive reading and writing, streamed
  assets, full integrity verification on open, and local save with atomic
  replacement.
- `documentkit-android`: Storage Access Framework import and export, with
  bounded staging and scoped cleanup.
- Kotlin Multiplatform JVM and Android targets sharing the archive
  implementation through one intermediate source set.
- CI on Linux, Windows and macOS with JDK 17 and 21, an Android assemble job,
  and an independent Gradle build that resolves the published artifacts and
  exercises them.

### Notable behaviour

- Opening verifies every declared byte against its length and SHA-256 before
  returning a handle.
- Limits bound bytes actually streamed, never sizes the archive declares about
  itself. Writers apply the same limits as readers.
- A local save is atomic or fails with `AtomicReplaceUnsupported`. There is no
  silent fallback to a copy.
- An interrupted save leaves the previous document byte-for-byte unchanged.
- Opening an older document migrates it in memory and never rewrites the file.
- Unknown model fields and unknown archive entries are rejected, not dropped.
- The container writer records no timestamps.

### Not yet implemented

The `inspect` / `validate` CLI, the Lantr legacy importer, benchmarks, fuzzing,
and Android instrumented tests. `documentkit-android` currently has no
automated tests: every entry point takes a `Context` or a `Uri`, and
instrumented coverage at API 24 and 36 is `0.2` work.
