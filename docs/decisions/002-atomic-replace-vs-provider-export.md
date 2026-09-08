# Atomic replacement versus Android provider export

DocumentKit saves to two very different destinations, and gives them two
different receipts. That asymmetry is the design, not an oversight.

## A local file: `SaveReceipt.AtomicReplace`

```
build in a sibling temp file → verify by opening it → force to storage → atomic move
```

Every step before the move is recoverable. The user's previous document is
untouched, byte for byte, until the last instant. Then one operation makes the
new document visible, and any reader sees either the old one or the new one.

Three details carry more weight than they look like they do.

**The staging file is a sibling of the destination**, not a file in the system
temp directory. `ATOMIC_MOVE` across filesystems is not possible, and the
system temp directory is frequently on a different one. Staging beside the
destination is what makes the last step available at all.

**The staged archive is opened and fully verified before the move**, using the
same code path an ordinary open uses. A file that would not load is never
allowed to become the user's document. This costs one extra full read per save
and has already justified itself in the tests: a model failing application
validation, and an asset whose bytes changed mid-save, both fail *before* the
destination is touched.

**There is no fallback to a copy.** If the filesystem cannot do an atomic move,
the save fails with `AtomicReplaceUnsupported`. Falling back would mean a
caller who asked for atomic replacement receives a truncated file on failure
and has no way to discover the guarantee was quietly downgraded. A named error
they can handle is worth more than a save that "worked".

### What atomic replacement is not

- **Not power-loss durability.** File contents are forced to storage, but Java
  offers no portable way to fsync the containing directory. After a power cut
  the directory entry may not name the new file.
- **Not a network-filesystem guarantee.** SMB and NFS make their own promises
  about rename, and they are not this one.
- **Not concurrent-writer safety.** Callers must serialise writes to the same
  document and close old handles first. On Windows this is not advice: a move
  onto a file another handle holds open is refused outright, and the error says
  so.

After the move commits, the save succeeded — even if cancellation arrived
during the commit. Telling a caller that a committed save was cancelled would
make them try to recover something that is already correctly on disk.

## An Android content URI: `SaveReceipt.ProviderManagedExport`

Android's Storage Access Framework hands an application a URI, not a path. The
document may be on a removable card, in a cloud provider, or behind a custom
`DocumentsProvider` in another app. There is no sibling directory to stage in,
no rename primitive, and no guarantee the stream is seekable.

So the sequence is:

```
build in private staging → verify → open the provider's stream → copy → close
```

The verification still happens first, and for a sharper reason than on the
local path: **opening a provider output stream in `"wt"` mode truncates the
destination immediately.** That call is the point of no return. Anything that
could fail has to fail before it.

After it, the copy is exposed. An interruption partway through leaves the
destination holding a partial file, and DocumentKit cannot roll that back —
so when the copy fails, the error says the destination may contain partial
content. Reporting a bare failure would let a user believe their file was
untouched.

That is why this receipt is a different type. `AtomicReplace` and
`ProviderManagedExport` both mean "it saved", and they mean materially
different things about what happens when it does not.

## Why the sample says "Save a copy"

Because that is what the operation is. The Android sample offers export as
*Save a copy* rather than *Save*, since advertising it as a crash-safe
overwrite would be a claim the platform does not support.

An app that wants overwrite semantics on Android should keep its working
document in private storage — where atomic replacement genuinely is available —
and treat provider export as what it is: publishing a copy somewhere the user
chose.

## Import is asymmetric too

Reading through a provider requires copying the URI's stream into private
staging first, because verification reads a ZIP central directory and a
provider stream is not reliably seekable. The copy is bounded by the
archive-size limit: a provider serving an endless stream fills a bounded file
and then fails, rather than filling the device.

The resulting handle owns that staging copy and deletes it on `close`. Hard
process termination can still leave one behind, which is why
`cleanStagingDirectory()` exists — scoped to DocumentKit's own subdirectory of
the app cache, because a library that swept the whole cache for files it
guessed were stale would eventually delete somebody else's.

## The summary

| | Local file | Provider URI |
|---|---|---|
| Build and verify before touching the destination | yes | yes |
| Destination untouched on failure | yes | **no**, once the stream is open |
| Atomic visibility | yes, or a named error | no |
| Receipt | `AtomicReplace` | `ProviderManagedExport` |

Two destinations, two guarantees, two types. Collapsing them into one
"it saved" would be the easy API and the dishonest one.
