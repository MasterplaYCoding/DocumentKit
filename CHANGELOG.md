# Changelog

Notable changes are recorded here, following
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Until `1.0` the
public API may change between minor versions; the container format will not
change without a `container_version` bump and a migration note.

## [0.3.0] - unreleased

The date becomes real on the day it is tagged; see [RELEASING.md](RELEASING.md).

### Fixed

- **The overflow guard on declared lengths overflowed.** A manifest with a
  negative asset length made `Long.MAX_VALUE - length` wrap to
  `Long.MIN_VALUE`, so every total compared greater and the file was
  additionally accused of an overflow that had not happened. Over-reporting
  rather than under-reporting, but the point of collecting every problem is
  that each one is real.
- **Two copies of the same wrapping sum.** `Manifest.declaredTotalLength()`
  and `DocumentSummary.declaredContentBytes` each carried their own
  `documentLength + assets.sumOf { it.length }`, and both turned lengths that
  add past `Long` into a small - or negative - number, which satisfies any
  check phrased as "the declared total is under N". That is the only thing an
  advisory total is used for. There is now one implementation, saturating and
  ignoring negative lengths, and both types call it. Neither was reachable with
  an overflowing manifest through a reader - `DocumentStore` validates before
  it summarises - but both types are public and constructible.

### Added

- **`ManifestValidationTest`**, covering every rejection `Manifest.validate()`
  can make. Nothing referenced that function before: fifty lines standing
  between a hostile manifest and the rest of the reader, indistinguishable
  from a function returning an empty list, and passing every other test in the
  repository either way. It is what found both fixes above.
- **Ten more containers in the malformed-input corpus**, at the archive level
  rather than the manifest: an entirely empty but structurally valid ZIP, an
  entry with no name, format entry names spelled in the wrong case, manifests
  that parse as JSON but are not objects, a manifest carrying a byte order
  mark, a manifest missing a required field, and both halves of the ZIP
  polyglot pair - bytes before the archive and bytes after it. The library
  already handled all ten; what they add is that it is now recorded, rather
  than true by accident.
- A row in the README's guarantees table for what the polyglot cases
  established: reading is unaffected by bytes around the archive, and
  DocumentKit does not certify that a file is *only* a container.
- **The hostile corpus, run through `documentkit-cli`.** Ten broken containers
  as files on disk, asserting that `validate` calls each one invalid, that
  `inspect` never answers with an invocation error, that every verdict carries
  a reason, and that `--json` stays one parseable object. The library's own
  tests prove each file is refused; none of them said what happens when the
  refusal has to travel out through a command, where an escaping exception
  reaches a user as a stack trace and an exit code nobody chose.
- `DocumentSummaryTest`, exercising the saturating total directly - the route
  a caller takes when they build a summary rather than read one.
- `FormatTest`, which pins that `isKnownEntry` is a shape filter rather than a
  safety check. It returns true for `assets/../../escape.txt`, because the
  prefix matches; what keeps such an entry out is that `assetPath` derives
  every path from an already-validated `AssetId`, so a crafted name matches
  nothing. The function's name invited the opposite assumption and its KDoc
  encouraged it. Both now say filter.

## [0.2.0] - 2026-09-10

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

- **`samples/lantr-import`**: reads a Lantr `.ltrn` archive and writes a
  DocumentKit container. Not published and not part of the supported surface -
  it exists so the extraction PROVENANCE.md describes is executable rather than
  asserted. It reports every piece of source content it did not carry across,
  by name and location.

- **`documentkit-cli`**, with `inspect` and `validate`. Works on a container
  belonging to any application, because neither command needs a codec - and
  neither claims more than it checked: validation covers container structure
  and integrity, and says in its own output that the application schema was
  not examined.
- **Real API documentation.** Dokka 2.2 generates the javadoc jars, which were
  previously empty stubs because AGP 8.9 bundles a Dokka that crashes on
  Kotlin 2.2 sources. `verifyPublishedCoordinates` now also rejects a jar that
  is present but empty - the stub satisfied a presence-only check while giving
  consumers nothing.
- `DocumentStore.inspect` and `DocumentStore.validate`: codec-free reading of
  what a container declares, and a full integrity check that collects every
  problem rather than stopping at the first.

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

Benchmarks and fuzzing.

`documentkit-android` has no *instrumented* tests. Every entry point takes a
`Context` or a `Uri`, so coverage at API 24 and 36 needs an emulator and is
`0.3` work. It is not untested: `consumer-check-android` resolves the published
artifact and compiles against the whole Android surface on every CI run, which
is what closes the gap that mattered — the module existing at all.
