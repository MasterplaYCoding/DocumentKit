# Security

## Reporting a vulnerability

Report privately through GitHub's
[private vulnerability reporting](https://github.com/MasterplaYCoding/DocumentKit/security/advisories/new),
not as a public issue.

Expect acknowledgement within a week. Advisories credit the reporter unless
they ask otherwise.

## What is in scope

DocumentKit parses files that arrive from anywhere: email, downloads, shared
drives, sync folders, Android content providers. The parser is the attack
surface, and all of these are vulnerabilities:

- **Resource exhaustion from a crafted archive.** Unbounded memory, unbounded
  disk use, or a read that does not terminate. Limits apply to bytes actually
  streamed, so any case that bypasses them is a real finding.
- **Writing outside the intended destination.** Archive paths are derived from
  validated asset ids rather than read from the container; any path by which a
  container influences where bytes land is a vulnerability.
- **A crash an application cannot handle** — a `StackOverflowError` from
  deeply nested JSON, say, or an uncaught error where a `DocumentError` is
  documented.
- **Content accepted without verification.** Any route by which an entry
  reaches an application without its length and digest being checked.
- **Data loss on the save path.** Anything that leaves the previous document
  damaged or missing after a failed save to a local filesystem.
- **Document contents in error messages.** Errors name entries and migration
  steps, never contents. A leak of user data into an error string is a finding.

## What is not in scope

- **Digests are not signatures.** The manifest's SHA-256 values detect
  corruption and mismatched content. Anyone who can rewrite the content can
  rewrite the manifest. The format has no authentication and claims none, so a
  report that a document can be modified by someone with write access to it is
  not a vulnerability.
- **No encryption.** Containers are not encrypted and assets are stored as-is.
- **Provider-side behaviour on Android.** Once bytes are handed to a content
  provider, what that provider does with them is outside this library.
- **Power-loss durability.** File contents are forced to storage, but Java
  offers no portable directory fsync, and the documentation says so rather
  than implying otherwise.

## Fuzzing

Bounded JVM fuzzing over the archive reader is planned before `1.0`. Every
crash it finds becomes a minimised regression fixture in the malformed-input
corpus.
