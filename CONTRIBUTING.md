# Contributing

## Getting set up

```bash
./gradlew build
```

JDK 17. The Android modules need an Android SDK; point Gradle at it with a
`local.properties` containing `sdk.dir=…`, or set `ANDROID_HOME`.

To check the published artifacts rather than the source tree:

```bash
./gradlew publishToMavenLocal
```

```bash
cd consumer-check && ./gradlew run
```

## What a change needs

- **A test that fails without it.** For a parser or archive change, that means
  a generated fixture in `MalformedInputTest`, not a committed binary.
- **A guarantee documented next to its limit.** This is a house rule. A
  persistence library that overstates what it promises is worse than one that
  promises less.
- **No new claim in `docs/format-v1.md` without an implementation and a test.**
  The specification is normative.

## Things that will be pushed back on

- Trusting metadata inside an untrusted file to decide how much work to do.
  See [003](docs/decisions/003-untrusted-size-metadata.md).
- Loading a whole asset into memory. Assets stream, in both directions.
- Extracting anything to a shared temporary directory, or registering files
  with `deleteOnExit`. Every temporary file has an owner that deletes it.
- Falling back to a non-atomic copy when an atomic move is unavailable.
- Inventing a value when a stored one is malformed — a fresh UUID, the current
  time, a default title. Report the error.
- `ignoreUnknownKeys = true` anywhere in the read path.
- Timestamps written by the container writer.
- Java or Android imports in `documentkit-core`.

## Small, real contribution opportunities

1. **`inspect` and `validate` CLI subcommands** (`documentkit-cli`, planned for
   `0.2`). The reading and validation logic already exists; this is argument
   parsing, output formatting and exit codes — 0 for a valid document, 1 for an
   invalid one, 2 for an invocation failure.
2. **Android instrumented tests** against a test `DocumentsProvider`: import,
   export, a null stream, failure midway through a copy, permission denial and
   cancellation. Currently the largest untested surface in the project.
3. **More malformed fixtures.** Anything the corpus does not yet cover — ZIP64
   edge cases, unusual compression methods, encrypted entries. Every new crash
   becomes a regression fixture.
4. **A benchmark harness** for three workloads: a small document, many small
   assets, and one large streamed asset. Report elapsed time, peak observed
   memory, workload and runtime separately — never a single headline number.
5. **Media-type helpers.** `AssetEntry.mediaType` is advisory and currently
   unpopulated. A small helper for callers who want to set it consistently
   would be useful, as long as the library still never decodes an asset.

Larger items — the CLI, the Lantr legacy importer, fuzzing — are on the roadmap
in the README. Please open an issue before starting one, so the API shape can
be agreed first.
