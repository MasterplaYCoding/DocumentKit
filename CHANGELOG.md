# Changelog

Notable changes are recorded here, following
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Until `1.0` the
public API may change between minor versions; the container format will not
change without a `container_version` bump and a migration note.

## [Unreleased]

Working toward `0.1.0`, the first published release. See
[RELEASING.md](RELEASING.md) for what publishing requires.

### Fixed

- **`documentkit-android` published no artifact at all.** It applied
  `maven-publish` and configured `singleVariant("release")` but never
  registered a `MavenPublication`, which AGP does not do for you. The module an
  Android application needs did not exist in any repository. CI could not
  notice: a JVM consumer cannot resolve an AAR.
- **Structured errors carried document contents.** `DocumentError.InvalidJson`
  embedded `kotlinx.serialization`'s exception message verbatim, and those
  messages quote the offending value or a slice of the raw JSON. Errors are now
  reduced to the failure kind and the field name, which contradicts nothing a
  reader needs and leaks nothing they should not have.
- **`kotlinx-coroutines` was declared `implementation`, not `api`**, despite
  appearing in `DocumentStore`'s and `DocumentTransfer`'s public constructors.
  Consumers had to depend on it themselves, and nothing said so.
- Every README Kotlin snippet, which previously could not compile: `save` and
  `open` are `suspend` functions written as top-level statements, with six
  undefined identifiers and no imports for an API spanning two packages.

### Added

- `verifyPublishedCoordinates`: publishes to a build-local repository and
  fails if any expected artifact — POM, module metadata, sources jar, javadoc
  jar — is missing for any module.
- `consumer-check-android`: an independent Android build that resolves
  `documentkit-android` by coordinates and compiles against it.
- Maven Central publishing, a tagged release workflow, and
  [RELEASING.md](RELEASING.md).
- [docs/troubleshooting.md](docs/troubleshooting.md): what each structured
  error means and what to do about it.
- A Getting started section in the README: repository, coordinates, the
  required `kotlinx-serialization` plugin, and which module to depend on.

### Previously added, toward the 0.1 engineering gate

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

The `inspect` / `validate` CLI, real API documentation (AGP's bundled Dokka
crashes on Kotlin 2.2, so javadoc jars are empty stubs for now), the Lantr
legacy importer, benchmarks, and fuzzing.

`documentkit-android` has no *instrumented* tests. Every entry point takes a
`Context` or a `Uri`, so coverage at API 24 and 36 needs an emulator and is
`0.3` work. It is not untested: `consumer-check-android` resolves the published
artifact and compiles against the whole Android surface on every CI run, which
is what closes the gap that mattered — the module existing at all.
