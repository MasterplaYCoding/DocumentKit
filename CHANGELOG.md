# Changelog

Notable changes are recorded here, following
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Until `1.0` the
public API may change between minor versions; the container format will not
change without a `container_version` bump and a migration note.

## [Unreleased]

### Added

- **A mutation fuzzer for the reader, and the regressions it found.**
  `FuzzTest` takes valid containers - deflated and stored, with assets and a
  legacy one that migrates - mutates them a few bytes at a time, and checks
  `open`, `inspect` and `validate` against the contract `MalformedInputTest`
  states: success or a named `DocumentException`, never another exception, a
  hang, or a mutated container that opens with different content. Three
  strategies, because raw byte flips almost never get past the ZIP layer:
  raw bytes; `manifest.json` rebuilt into a valid ZIP (the manifest is not
  digest-protected); and `document.json` re-digested, so the mutation
  reaches the codec and migrations.

  Every build runs 1,500 iterations on a fixed seed. `./gradlew
  :documentkit-io:fuzzSweep` runs 50,000 (or `-PfuzzIterations=`) on a fresh
  seed it prints, and CI runs 200,000 weekly, keeping findings as an
  artifact. Inputs that found a bug live in
  `documentkit-io/src/jvmCommonTest/fuzz-regressions/`, and
  `FuzzRegressionTest` replays them on every build - the roadmap's "fuzz
  regressions".

- **A compatibility corpus written by the released binaries.**
  `tools/compat-writer` is a standalone build that fetches a *released*
  `documentkit-io` from Maven Central and saves one fixed document; its output
  for 0.2.0, 0.3.0 and 0.4.0 is committed, and `CompatibilityCorpusTest`
  requires every build to open all of them to the same model and asset, and
  to write the same manifest digests for the same content. Every other test
  writes its fixtures with the current code, so none of them could notice a
  change that stopped users' existing files from opening. The check is
  semantic, as the format specification defines compatibility: the three
  files differ only in ZIP entry times, which the specification places
  outside the contract.

### Fixed

- **Damage in an entry's local header escaped as `ZipException` or
  `EOFException`.** `java.util.zip` lists entries from the central directory
  and reads an entry's local header only when its bytes are first read. A
  file whose directory was intact and whose local header was not therefore
  passed every structural check, then threw from the middle of `open`,
  `inspect` or `validate` - past every `catch (e: DocumentException)` the
  README tells callers to write. The fuzzer's first run found it six ways
  within 1,500 iterations; the hand-written corpus never corrupted a local
  header, only the directory.

  Every read of archive bytes now goes through one function that turns
  structural damage into `InvalidEntry`, naming the entry, and a plain I/O
  failure into `IoFailure` - a failing disk is not a damaged document. The two
  representative inputs are committed as regressions, each pinned to its
  exact error; reverting the fix fails all three regression tests.

- **`readAsset` trusted a size the file declared.** The reader has never
  trusted ZIP size fields - it streams and verifies the real bytes, which is
  why `docs/decisions/003` exists. But `readAsset` used `readBytes()`, which
  sizes its first buffer from `available()`, and `java.util.zip` answers
  `available()` with the entry's *declared* size. A container whose content
  verified perfectly and whose central directory claimed 2 GB for a 300-byte
  asset opened fine, then died in `readAsset` with `OutOfMemoryError:
  Requested array size exceeds VM limit` - an unbounded allocation driven by
  the file, in a function whose whole job is to be the simple option. Entry
  streams now report `available()` as 0, which its contract allows, so
  neither `readAsset` nor a caller's own `openAsset(…).readBytes()` can be
  steered by the header. Found by the first 100,000-iteration fuzz sweep.

- **A codec function that threw on a hostile document escaped `open`.**
  `open` runs application code on untrusted input: the codec's `validate`
  and `referencedAssets`. The README's own example builds asset ids with
  `AssetId.of`, which throws on a malformed one, so a document giving a note
  the image id `c]over` made a correct application's codec throw
  `IllegalArgumentException` straight out of `open` - with the id in the
  message. Both are now reported as `ApplicationValidationFailed`, naming the
  function and the exception's class but not its message, which may quote
  the document: errors DocumentKit writes carry no document content. A
  rejection `validate` *returns* is still passed through as written. Found by
  the same sweep, through the strategy that re-digests `document.json` so
  mutations reach the codec.

## [0.4.0] - 2026-09-11

`documentkit-android` tested at API 24 and 36, and a fix for handles leaked by a
cancelled open - the one change here that alters shipped behaviour; see *Fixed*.

### Added

- **`documentkit-android` is tested, at API 24 and API 36.** Until now the
  module was proven to *resolve* by a consumer build and nothing more: the
  Storage Access Framework code had no tests, the largest untested surface in
  the project. `DocumentTransferTest` runs under Robolectric - the framework
  code each API level ships, on the JVM - against a real `ContentProvider`
  reached through Android's own `ContentResolver`, so `openInputStream` and
  `openOutputStream` take the device path to a `ParcelFileDescriptor`. It
  covers a round trip with assets, the staging copy the handle owns, a
  provider that returns no stream, a revoked permission, a deleted document,
  failure partway through either copy, an endless stream against the archive
  limit, verification before the destination is opened, cancellation, and
  staging cleanup that touches nothing else in the cache.

  Two API levels, not one, and it showed why at once: making export open its
  destination with `"w"` instead of `"wt"` fails at API 36 and passes at API
  24, because older Android truncated on plain `"w"` and newer Android does
  not. An export written and tested against an old device would pass there
  and leave the old file's tail behind on a new one.

  Every one of the behaviours was confirmed to fail its test when broken on
  purpose. The roadmap said *instrumented* tests; the README says what
  Robolectric does not reach - real provider apps, real storage, process
  death - and CONTRIBUTING keeps on-device tests as open work.

  The tests need Java 21 to simulate API 36, so the module's test task runs on
  a Java 21 toolchain, downloaded by Gradle where absent; the library is still
  compiled for, and published as, Java 17. API 36 also needs
  `--add-exports java.base/jdk.internal.access` for Robolectric's
  file-descriptor stand-in - test JVM only.

- **The container specification is now machine-checked.**
  `docs/format-v1.md` calls itself normative and was, until now, prose sitting
  beside an unrelated implementation - accurate on the day it was written and
  free to drift silently afterwards. `SpecificationConformanceTest` parses the
  published limits table and asserts the build applies those exact numbers,
  rather than restating them in Kotlin, which would only prove a copy matches
  itself.

  It also checks what a written container actually contains: exactly the three
  documented entry kinds and no others, the serialised manifest field names
  rather than the Kotlin property names - a dropped `@SerialName` renames a
  field in every file the library has ever written and only a reader finds out -
  and that no timestamp has crept in, which the specification rules out and
  gives its reasons for.

  Confirmed to catch drift: changing the entry-count default from 2,048 to
  4,096 without touching the table fails it.

- `OpenedDocumentResourcesTest`: the last guarantee row with no test behind it.
  A leaked archive handle does not look like a leak - on Windows it locks the
  file, so the symptom is a save that cannot replace the document the user is
  editing, which is the entire lifecycle of an editor. Covers deleting after
  close, saving over a document just read, the failure path through `use`,
  fifty open/close cycles, two independent handles, closing twice, reading an
  asset after close, and a failed open, which is where a handle is likeliest to
  escape. Confirmed falsifiable: leaving one handle unclosed fails it on
  Windows. On Linux every one of these passes with a leak, which is why it
  matters that CI runs all three platforms.

- **A memory ceiling test.** The README has always said an asset costs a buffer
  rather than its own size in heap — it is why `AssetSource` is a stream
  factory, why the reader streams entries, and why limits count streamed bytes.
  Nothing verified it, and every existing test uses assets small enough to
  buffer without anyone noticing, so a single well-meaning `readBytes()` in the
  wrong place would have removed the property with a green suite.

  `memoryCeilingTest` saves, reads and validates a **512 MiB** asset in a JVM
  given a **192 MiB** heap. It measures nothing: if any stage materialises the
  asset the run dies with an `OutOfMemoryError`, which is a clearer signal than
  a number that drifted. A separate Gradle task because the heap is the
  assertion — under `jvmTest`'s default heap the same test passes whether the
  library streams or buffers, which would be worse than having no test at all.

  Verified falsifiable: swapping the streaming drain for `readAsset` fails it
  with `OutOfMemoryError` on the read, while the save half still passes, which
  independently confirms the write path streams. It runs on all six CI test
  jobs and gates a release.

  Nothing large touches the disk. The bytes are generated on demand and are
  repetitive, so deflate keeps the archive under a megabyte; only the stream is
  large.

- `AssetSourceTest`: the contract that openStream is called **twice** per save,
  once to measure and once to write, which nothing in the type signature says
  and which a one-shot source satisfies at compile time. The save already
  detects a source that cannot be read again and refuses rather than shipping a
  container whose manifest describes bytes it does not contain - now pinned,
  along with the properties that make that refusal safe: no destination file
  created, no staging file left behind, and a previous document byte-identical
  afterwards.
- `DocumentEncodingTest`: `DocumentCodec.encode` had no test at all, despite
  producing the bytes every saved document is made of. Covers the two
  properties the format depends on - encoding is deterministic, so two saves of
  identical content are identical files and the manifest digest means
  something; and defaults are written out rather than omitted, so a build that
  changes a default cannot silently reinterpret a document saved before it.
- Migration privacy tests: one showing a migration's message reaching
  `MigrationFailed.reason` with document content in it, one showing that the
  failures DocumentKit words itself — a gap in the chain, a document from the
  future — contain nothing from the document, and one covering an exception
  with no message at all, which would otherwise produce an error that says a
  step failed and nothing else. They back the narrowed privacy claim under
  *Fixed*.

### Fixed

- **A caller cancelled as a document finished opening leaked it.**
  `DocumentStore.open`, `openStaged` and `buildVerifiedArchive` ran on the
  store's dispatcher through `withContext`, which has a prompt cancellation
  guarantee: a caller cancelled while the block runs gets
  `CancellationException` *even when the block completed*, and what the block
  returned is discarded. What these return is an open archive handle - which
  on Windows keeps the file locked until a garbage collection happens to
  finalise it, so the next save cannot replace it - and, from `openStaged`
  and `buildVerifiedArchive`, a file in a staging directory that nothing
  would ever delete. `DocumentTransfer.import` wrapped the same pattern once
  more. An editor cancels opens routinely: the user taps another document
  before the first finishes loading.

  Each now keeps a reference outside `withContext` and closes or deletes what
  it produced if cancellation discards it. `CancelledOpenTest` (JVM) and
  `DocumentTransferTest` (Android) force the race deterministically - the
  codec's own `validate` cancels the caller, so the open completes and the
  cancellation is only noticed on the way out - and failed before the fix:
  the staging copy survived, the built archive survived, and on Windows the
  opened file could not be renamed. `import`'s own guard covers a narrower
  window, between `openStaged` returning and `import` resuming, that cannot
  be forced on cue; it is there by construction, and not claimed as tested.

- **The privacy claim was broader than the guarantee.** The README said errors
  "do not carry document contents" and named migration steps in the same
  sentence, while `MigrationChain` passes a migration's own exception message
  through verbatim — and this repository's own test already asserted that it
  does. Someone reading the claim would reasonably paste a migration failure
  into a public issue. The wording now separates the half DocumentKit
  guarantees from the half the application controls, and the guarantees table
  carries the same boundary.

### Changed

- **Benchmarks are off the roadmap**, replaced by ceiling tests such as
  `memoryCeilingTest` above: a benchmark produces a number nobody is obliged
  to act on, a ceiling fails the build. The README's `0.4` row and
  CONTRIBUTING's list say so. CONTRIBUTING also still offered the
  `inspect`/`validate` CLI and the Lantr importer as open work; both shipped
  in `0.2.0`.

## [0.3.0] - 2026-09-10

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
