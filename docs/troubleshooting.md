# Troubleshooting

Every expected failure arrives as a `DocumentException` carrying a
`DocumentError`. Branch on the error type, not on the message text — messages
are documentation, the types are the contract.

```kotlin
catch (failure: DocumentException) {
    when (val error = failure.error) {
        is DocumentError.IntegrityMismatch -> …
        else -> error.detail
    }
}
```

---

## `AtomicReplaceUnsupported`

The save built and verified a complete archive and then could not put it in
place. **Your previous document is untouched** — that is the guarantee this
error exists to preserve, since the alternative is a silent fallback that hands
you a truncated file while reporting success.

Three common causes:

**The destination is open somewhere.** On Windows a move onto a file another
handle holds is refused outright. Close any `OpenedDocument` on that path
first. This is the most likely cause by a wide margin, and the reason
`OpenedDocument` is `Closeable` and the examples all use `use { }`.

**The staging file and the destination are on different filesystems.** Saving
stages a sibling temporary file precisely to avoid this, so it should not
happen for an ordinary path — but a destination inside a mount point, a
symlink crossing devices, or a container bind mount can still split them.

**The filesystem does not support atomic moves at all.** Some network
filesystems do not. There is no workaround inside the library; the honest
options are to save somewhere local and copy, and to tell the user the
destination cannot be written safely.

## `"… is already being written through this store"`

Two saves to the same destination overlapped on one `DocumentStore`. The store
refuses rather than letting them interleave.

It can only see writes going through itself. Two `DocumentStore` instances, or
two processes, are not coordinated — the library does not do multi-process
write coordination, and says so. Serialise writes to a document in your own
application.

## `IntegrityMismatch`

A declared length or SHA-256 did not match the bytes actually read. The
document is damaged or was modified after it was written — truncated by a
failed transfer, corrupted in storage, or edited by something that did not
update the manifest.

This is **not** an authentication failure. Digests detect corruption and
mismatched content; anyone who can rewrite the content can rewrite the
manifest. The format has no signatures.

## `InvalidJson`

The manifest or `document.json` is not valid UTF-8, nests deeper than
`maxJsonDepth`, is malformed, or does not match your model.

The message names the entry and, where the serializer identified one, the
field — but never the value. That is deliberate: errors end up in logs and bug
reports, and the user's document is theirs.

The most common cause during development is an **unknown field**. DocumentKit
sets `ignoreUnknownKeys = false`, so a document written by a build that knows
more of your schema is rejected rather than silently stripped. Dropping the
field would lose the user's data on their next save. Raise your
`schemaVersion` and add a migration instead.

## `MissingMigration` / `UnsupportedSchema`

`MissingMigration` means the document is older than your registered chain can
reach — the chain must be complete and gap-free from the document's version up
to the codec's. `UnsupportedSchema` means it is *newer* than this build
understands, which nothing can fix except updating the application.

Both leave the source file untouched.

## `LimitExceeded`

A limit in `DocumentLimits` was reached while streaming. Limits apply to bytes
actually read, not to what the archive declares about itself.

**The trap:** writers apply the same limits as readers. Raising a limit on the
save side alone produces files your own reader will reject. Configure one
`DocumentLimits` and use it for both:

```kotlin
val limits = DocumentLimits(maxAssetBytes = 256L * 1024 * 1024)
val store = DocumentStore(limits)
```

## `MissingReferencedAsset`

Your codec's `referencedAssets` named an asset the container does not hold.
On save this means the asset was not in the `assets` map — it is never silently
omitted. On open it means the document is inconsistent.

Assets that are present but *not* referenced are preserved, not deleted: an
older build of your application must not discard data a newer one added.

## `WrongApplication`

The document belongs to a different `applicationId`. DocumentKit refuses rather
than best-effort decoding a file that was never yours.

---

## Not an error, but surprising

**One handle is not safe for concurrent use.** A ZIP handle has a position;
two threads reading assets through the same `OpenedDocument` will interleave.
Independent handles on the same file are fine.

**Do not modify the file while a handle is open.** The handle reads from it
lazily after verification.

**`cleanupWarning`.** If `close()` could not delete a staging file it owned,
the reason is left on the handle rather than thrown — failing to close a
document should not lose the document. On Android, `cleanStagingDirectory()`
clears what a hard process termination left behind.

**Android export is a copy, not a replacement.** `ProviderManagedExport` and
`AtomicReplace` are different types because they carry different guarantees.
See [002](decisions/002-atomic-replace-vs-provider-export.md).
