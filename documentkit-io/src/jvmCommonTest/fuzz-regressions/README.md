# Fuzz regressions

Inputs `FuzzTest` found a bug with. `FuzzRegressionTest` replays every `.dkit`
here on every build: `open`, `inspect` and `validate` must each succeed or throw
a `DocumentException`, and nothing else.

A fuzzer run is a search, and its seed moves on — the input that found a bug is
never generated again. Committing it is what turns a finding into a test.

To add one: take the file from `documentkit-io/build/fuzz-findings/` (written by
`./gradlew :documentkit-io:fuzzSweep`, or by the weekly CI sweep as an
artifact), fix the bug, give the file a name saying what is wrong with it, and
add a test in `FuzzRegressionTest` pinning the exact error.

| File | What is wrong with it | Used to escape as |
|---|---|---|
| `local-header-bad-signature.dkit` | An entry's local header does not start with `PK\3\4`; the central directory is intact. | `java.util.zip.ZipException` |
| `local-header-past-end.dkit` | An entry's local header points past the end of the file. | `java.io.EOFException` |
| `referenced-asset-id-malformed.dkit` | `document.json` (re-digested, so it passes integrity) gives a note the image id `c]over`; the codec's `referencedAssets` builds it with `AssetId.of`, which throws. | `IllegalArgumentException`, quoting the id |
| `declared-size-huge.dkit` | `assets/cover` claims 2,147,483,948 bytes in the central directory and has 300. The content verifies, so it opens; `readAsset` then sized a buffer from the claim. | `OutOfMemoryError: Requested array size exceeds VM limit` |
