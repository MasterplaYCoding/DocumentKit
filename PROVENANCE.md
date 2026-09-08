# Source provenance

DocumentKit was extracted from Lantr, a Kotlin Multiplatform presentation
editor. This file records what was adapted, from which revision, and what was
deliberately replaced rather than carried over.

## Lantr

- Project: [Lantr](https://github.com/MasterplaYCoding/Lantr), MIT licensed.
- Revision at the time of extraction: `5fb52b877933da5451ddad4519c6b6190eee3c1d`
  (2026-08-22).
- Copyright: © 2026 Matei Ursache and Lantr contributors. The MIT notice is
  retained in [LICENSE](LICENSE).

### Files consulted

| Lantr source | What informed DocumentKit |
|---|---|
| `composeApp/src/jvmMain/.../Util/LtrnArchiveService.kt` | Bounded entry reads, JSON entry helpers, ZIP write structure, the entry-name validation rules |
| `composeApp/src/androidMain/.../Util/LtrnArchiveService.kt` | URI-to-cache staging and the staged-export shape |
| Lantr archive documentation | Historical context and fixture layout only |

### Imported symbols

No Lantr symbol was copied verbatim. The following behaviours were adapted:

- `validateArchive`'s entry-name checks (backslash and `..` rejection) became
  `ArchiveInventory.validate`, extended with duplicate-name detection,
  directory-entry rejection, an allowlist of known entries, and an entry count
  limit applied before the walk.
- `readEntryBytes`'s bounded read became `InputStream.readAtMost`, now counting
  actual streamed bytes rather than consulting `ZipEntry.getSize()`.
- `writeJsonEntry` / `writeBinaryEntry` became `DocumentStore.writeEntry` and
  the streaming asset-write path.
- The Android `copyUriToCache` / staged-export structure informs the
  `documentkit-android` module's import and export operations.

### Behaviours deliberately replaced

Each of these was present in the original and is *not* carried across.

| Lantr behaviour | Replacement, and why |
|---|---|
| Opened the destination output stream before the save was ready | The complete archive is built and verified in a sibling temporary file, and only then moved atomically. An interrupted save now leaves the old document intact. |
| Trusted the sum of `ZipEntry.getSize()` as the aggregate protection | Limits apply to bytes actually streamed. An archive's own claims about its size cannot be the protection against it being too large. |
| `imageFile.readBytes()` loaded whole assets into memory | Assets stream through a 64 KB buffer, in both directions. |
| Flattened archive asset paths to filenames (`substringAfterLast('/')`) | Asset ids are a validated value class, and archive paths are derived from them. Two assets in different directories can no longer collide into one file. |
| Silently skipped a missing image on save (`if (imageFile.exists())`) | A referenced asset that is absent fails the save with `MissingReferencedAsset`. There is a test for it. |
| `safeUuid` and `parseDate` invented a fresh UUID or the current time when a stored value was invalid | Malformed identifiers are reported as `InvalidManifest`. Inventing an id silently turns one document into a different document. |
| Dropped unrecognised blocks and used `ignoreUnknownKeys = true` | `ignoreUnknownKeys` is off. An unknown field means the file came from a build this one does not fully understand, and dropping it loses user data on the next save. |
| `Files.createTempDirectory(...)` with `deleteOnExit()` for extracted images | Nothing is extracted on open. Assets stream from the verified archive to a caller-chosen destination, and an opened document owns and deletes its own staging file on `close`. |
| Timestamps written into the archive by the writer | The manifest carries no timestamps. Display metadata belongs to the application's model. |

### Not reused

- Every Lantr model type: `Presentation`, `Slide`, `Theme`, `Rhetoric`,
  `Arc`, `StoryBlock` and the rest. None of them appear anywhere in
  DocumentKit, and none of them can: the core module has no Java, no Compose
  and no application-specific types at all.
- Lantr's image editing and global image registration.
- Compose, `java.util.UUID` and `java.util.Date` requirements in the core.

The Lantr importer sample, planned for `0.3`, will parse the `.ltrn` layout for
supported fixture content only. It will never write `.ltrn`, and there is no
bidirectional compatibility promise.

## GOATalking

No GOATalking code is used here. EventLab, the sibling project, records its own
adaptation of that codebase.

## Dependencies

`kotlinx.serialization` (Apache 2.0) and `kotlinx.coroutines` (Apache 2.0).
Versions are pinned in `gradle/libs.versions.toml` and the Gradle wrapper is
committed.
