# Compatibility

What DocumentKit promises from one version to the next, and what enforces each
promise. A promise nothing enforces is marked as such — a compatibility policy
is only as good as the build that would fail if it were broken.

## Saved files

Users keep files for years. Nothing else here is as expensive to break, so it
is the strongest promise and the most heavily checked.

| Promise | Enforced by |
|---|---|
| Every release opens every container every earlier release wrote. | `CompatibilityCorpusTest`, against files written by the **released** 0.2.0, 0.3.0 and 0.4.0 artifacts from Maven Central (`tools/compat-writer`). Each release adds its own file. |
| For the same content, a release writes the same document and asset digests as the releases before it. | The same test: it saves the corpus document with the current build and compares manifests. |
| What a writer produces changes only with a `container_version` bump, which comes with an updated [format specification](format-v1.md), a changelog migration note, and a reader that still opens every earlier version. | The corpus test above, and `SpecificationConformanceTest`, which parses the specification's limits table and checks what a written container contains. |

Why even an *additive* change needs a bump: the reader rejects manifest fields
and archive entries it does not recognise, by design (the specification
explains why), so a new optional field would make every older release refuse
the new file. Compatibility runs **forwards only** — an older release refuses a
`container_version` it does not know, with `UnsupportedContainer`, rather than
guessing.

Your own document model is versioned by `schema_version` and your migrations.
DocumentKit promises that the chain runs — complete, gap-free, validated when
the codec is built — not what your migrations do.

**Not promised:** byte-identical archives. ZIP metadata such as entry times
differs between saves; the specification's *Byte-level reproducibility*
section says so.

## The Kotlin API

The public API is the committed `api/*.api` dump of each published module —
`documentkit-core` and `documentkit-io` for JVM and Android separately,
`documentkit-android`, `documentkit-cli`. `apiCheck` runs in every build, in CI
and before every release, and fails when the compiled classes differ from the
dump; changing the API means regenerating it with `./gradlew apiDump`, so the
change arrives as a reviewed diff.

- **Before 1.0 (now):** the API may change in a minor release — `0.4` to
  `0.5` — and only with a changelog entry saying what changed and how to
  migrate. Patch releases do not change it.
- **From 1.0:** semantic versioning. Removing or changing a public signature
  happens only in a major release; additions in minor ones.

`DocumentError` is sealed, and **new subtypes may appear in a minor release**:
a new way for a file to be wrong is not a breaking change. Keep an `else`
branch when you `when` over it. The subtypes that exist, and their `code`
strings, are pinned below.

## Behaviour

| Promise | Enforced by |
|---|---|
| Every row of the README's *Guarantees, each with its limit* table holds. Narrowing a guarantee is a breaking change, versioned like an API removal; widening one is not. | Each row names the test behind it. |
| `DocumentError.code` strings are stable and safe to branch on. `detail` text is not — do not parse messages. | `ErrorCodeStabilityTest`: an exhaustive `when` over the sealed class, so a new subtype does not compile until its code is recorded, and a renamed code fails. |
| A damaged or hostile container fails with a `DocumentException`, never another exception. | `MalformedInputTest`, `FuzzTest` and the fuzz regressions it has found. |
| Errors DocumentKit writes never contain document content. | `ErrorPrivacyTest` and the privacy assertions in the codec and fuzz-regression tests. |
| The CLI exits `0` for a valid document, `1` for an invalid one, `2` for a bad invocation. | `CliTest`. |

## Platforms

JDK 17 or newer; Android API 24 or newer, compiled against 36; Kotlin 2.2.
Raising a minimum is a minor-version change before 1.0 and a major one after,
always announced in the changelog.

Tested: Linux, Windows and macOS on JDK 17 and 21; Android at API 24 and 36
under Robolectric — framework code on the JVM, not a device.

## Not covered

- The CLI's output text. Its exit codes are covered; its wording is not.
- Anything `internal`, the test fixtures, `samples/`, `tools/`, and
  `consumer-check*/`.
- The Lantr importer in `samples/`, which is an example of using the library,
  not part of it.
