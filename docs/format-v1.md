# DocumentKit container, version 1

This specification is normative. An implementation that accepts a container
this document says to reject is wrong, and so is one that rejects a container
this document says to accept.

## Layout

A container is a ZIP archive containing exactly these entries:

```
document.dkit
├── manifest.json
├── document.json
└── assets/
    ├── <asset-id>
    └── <asset-id>
```

Applications choose their own file extension. `.dkit` is a convention, not a
requirement, and the format carries no magic number beyond the ZIP header.

Directory entries are not part of the format and must be rejected. Any entry
whose name is not `manifest.json`, `document.json`, or a path under `assets/`
must be rejected — not ignored. An entry a reader does not understand is
content it would carry across a save without ever having verified it, or drop
without telling anyone.

## Asset ids

An asset id matches:

```
[A-Za-z0-9._-]{1,128}
```

and must not be `.`, `..`, or begin with `.`.

Archive paths for assets are **derived** from ids as `assets/<id>`. A reader
computes the expected path for each id in the manifest index; it never takes a
path from the archive and turns it into anything. Validating a path after
accepting it is a weaker guarantee applied later.

Ids are opaque. They are not derived from content, they do not imply a media
type, and the library never decodes an asset.

## manifest.json

```json
{
  "container_version": 1,
  "application_id": "example.notebook",
  "schema_version": 2,
  "document_id": "9c1f…",
  "document_length": 384,
  "document_sha256": "3b1a…",
  "assets": [
    { "id": "cover", "length": 20481, "sha256": "7f2c…", "media_type": "image/png" }
  ]
}
```

| Field | Meaning |
|---|---|
| `container_version` | This specification's version. Currently `1`. |
| `application_id` | Identifies the owning application. A reader rejects a document belonging to another. |
| `schema_version` | The *application's* schema version. Independent of `container_version`. |
| `document_id` | Caller-supplied stable identity. Never invented by a reader. |
| `document_length` | Actual uncompressed byte length of `document.json`. |
| `document_sha256` | Lowercase hex SHA-256 of `document.json`. |
| `assets` | Index of every asset present. |
| `assets[].media_type` | Optional and advisory. |

There are deliberately **no timestamps** and no display metadata. A writer that
stamps the current time makes two saves of identical content differ, and makes
the library the authority on something the application owns. A document's
"modified" date belongs in the application's own model.

### Digests

Digests detect corruption and mismatched content. They **do not authenticate an
author**: anyone who can rewrite the content can rewrite the manifest. The
format has no signatures and does not pretend to.

## Version rules

- `container_version` and `schema_version` are independent. Neither implies
  anything about the other.
- A container version greater than the reader supports is rejected.
- A document whose `application_id` differs from the codec's is rejected.
- A `schema_version` greater than the codec's is rejected. Guessing at a
  future format is how data gets lost.
- An older `schema_version` is migrated through a **complete** registered
  chain. A gap is an error, not something to skip over.
- Opening an older document **never** rewrites it. Migration happens in memory;
  writing is what `save` does.

## Asset rules

- The manifest index and the physical entries must agree in both directions.
  An indexed asset with no entry is a broken document. An entry with no index
  line is content that would never have been verified.
- Every asset named by the application's `referencedAssets` must exist.
  Missing assets fail validation on save; they are never silently omitted.
- Assets that are present and indexed but not currently referenced are
  **preserved**. An older build of an application must not delete data a newer
  build added.

## Reading

A conforming reader performs these steps, in order, before returning success:

1. Bound the archive file's own size.
2. Validate the entry inventory: count, names, duplicates, directories,
   unknown entries.
3. Read and validate the manifest.
4. Stream **all** declared content, checking actual lengths and digests.
5. Decode `document.json`, migrate it, decode the model, validate it.
6. Check every referenced asset exists.

Verification is complete before an application receives a handle. Asset access
afterwards may be lazy. The cost is reading the archive once on open; the
benefit is that a corrupt asset surfaces when the user opens the file rather
than an hour later when they scroll to it.

### Limits

Defaults, all configurable:

| Limit | Default |
|---|---|
| Archive file size | 256 MiB |
| Total actual uncompressed bytes | 256 MiB |
| Entry count | 2,048 |
| `manifest.json` | 1 MiB |
| `document.json` | 8 MiB |
| Any single asset | 64 MiB |
| JSON nesting depth | 128 |

Limits apply to bytes **actually streamed**, never to the sizes an archive
declares about itself. A container is untrusted input; its own claims about
how large it is cannot be the protection against it being too large. Reading
stops when a limit is reached, so a decompression bomb costs the limit rather
than the bomb.

Writers apply the same limits as readers. A writer permitted to produce a file
its own reader rejects is a trap that surfaces when the user tries to reopen
what they just saved.

JSON is pre-scanned for nesting depth before parsing, because a recursive
parser can exhaust the stack before any validation runs, and a
`StackOverflowError` is not something an application can handle.

## Writing

1. Serialise the model and stream assets into an owned staging workspace.
2. Compute actual lengths and digests while streaming.
3. Build the complete archive in a **sibling** temporary file — sibling,
   because the final move must stay within one filesystem to be atomic.
4. Close it, reopen it and verify it exactly as a reader would.
5. Force the file's contents to storage.
6. Replace the destination atomically.

The previous document is untouched until step 6. Anything that fails before
then leaves the user with exactly what they had.

### The guarantee, and its limits

**Atomic replacement** means atomic *visibility* on that filesystem: a reader
sees the old document or the new one, never a partial file.

It does **not** mean:

- power-loss durability,
- that directory metadata reached the disk (there is no portable directory
  fsync in Java),
- anything at all about network filesystems.

If the filesystem cannot perform an atomic move, a conforming writer reports
`AtomicReplaceUnsupported` and **does not** fall back to a copy. A caller who
asked for atomic replacement and silently received a truncated file would have
no way to find out.

Callers must serialise writes to the same document and close old handles before
replacing a file. This matters especially on Windows, where a move onto a file
another handle still holds open is refused.

## Byte-level reproducibility

Not promised. Two saves of the same content may produce different archive
bytes: ZIP metadata, entry ordering and compression details are not part of the
contract.

The compatibility contract is **semantic**: the decoded model, the asset
contents, and the manifest digests.
