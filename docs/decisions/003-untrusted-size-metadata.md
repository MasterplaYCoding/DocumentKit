# Validating archive contents without trusting size metadata

A document file arrives from somewhere. Email, a shared drive, a download, a
sync folder — it does not matter. The moment your application opens it, every
number inside it is an assertion made by whoever produced it.

This is the most consequential thing the extraction from Lantr changed, so it
gets its own page.

## The version that looks fine

```kotlin
val declaredSize = entries.sumOf { entry -> entry.size.coerceAtLeast(0L) }
require(declaredSize <= MAX_ARCHIVE_BYTES) { "Archive expands beyond the supported size" }
```

Read it charitably and it says: sum the uncompressed sizes of every entry, and
refuse anything over 256 MB. It looks like a resource limit. It reads like one
in review.

It is not one.

`ZipEntry.getSize()` returns a value stored *in the archive being validated*.
Nothing verifies it against the actual compressed data until that data is
decompressed. An archive can declare one kilobyte and expand to four gigabytes,
and this check passes it happily. That is the classic zip bomb, and it needs no
sophistication at all: a few hundred kilobytes of repeated zero bytes deflates
enormously, and the size field is just a number to overwrite.

The check is worse than absent, because it looks present. Nobody adds a real
limit next to code that already appears to enforce one.

## The rule

**Bound what you actually read, as you read it.**

```kotlin
while (true) {
    val read = input.read(buffer)
    if (read < 0) break

    total += read
    if (total > limit) {
        throw DocumentException(DocumentError.LimitExceeded(limitName, limit))
    }

    digest.update(buffer, 0, read)
    target?.write(buffer, 0, read)
}
```

Three properties fall out of this shape:

1. **The count cannot be forged.** It is the number of bytes that came out of
   the decompressor, not the number the file claims will.
2. **Reading stops at the limit.** A bomb costs the limit, not the bomb. There
   is no moment where the whole thing is decompressed and *then* measured.
3. **The digest is computed on the same pass.** Verification and bounding are
   the same walk over the data, so there is no window where content has been
   accepted but not checked.

The manifest's declared lengths are still checked — against the counted length,
as an integrity assertion. They are just never the *protection*.

## Where else metadata gets trusted by accident

Untrusted size is the headline, but the same instinct shows up elsewhere.

**Entry counts.** An archive with two million entries costs something merely to
enumerate. The count limit is applied *during* the walk and stops it, rather
than after building a list of everything.

**Overflow.** Summing declared lengths in a `Long` can wrap, and a wrapped
total compares as small. The manifest validator checks for overflow while
summing rather than trusting the arithmetic:

```kotlin
if (total > Long.MAX_VALUE - asset.length) { /* reject */ }
```

**Entry names.** The archive proposes a path; the reader must not turn it into
a filesystem path. DocumentKit derives `assets/<id>` from validated asset ids
and matches, so `../../.ssh/authorized_keys` has nothing to attach itself to.
Rejecting bad names is still done — a container carrying them is malformed and
saying so is useful — but the safety does not rest on that check.

**Duplicate entry names.** A ZIP can declare the same name twice, and which
copy a reader returns depends on the reader. Reject the archive. Note that
Java's own `ZipOutputStream` refuses to *create* one, which is exactly why the
test fixture for this case has to be written at byte level — the absence of a
convenient way to produce a hostile file is not evidence that nobody will.

**JSON depth.** `kotlinx.serialization`'s parser recurses. Deeply nested input
exhausts the stack before any validation runs, and a `StackOverflowError` is
not a condition an application can handle. So depth is pre-scanned in a single
non-recursive pass *before* parsing — a pass that has to track string literals
and escapes, because a `{` inside a user's text is not nesting.

**Character encoding.** `ByteArray.toString(Charsets.UTF_8)` substitutes
U+FFFD for invalid sequences rather than failing, so malformed bytes decode
into something plausible-looking. Round-tripping the decode catches it.

## What digests do and do not do

Every declared byte is checked against a SHA-256 in the manifest. That detects
corruption, truncation, and content that does not match what the manifest says.

It does **not** authenticate an author. Anyone who can rewrite the content can
rewrite the manifest; the digest is a checksum, not a signature. The format has
no signing, and the specification says so in as many words, because a digest
field is the sort of thing that gets mistaken for one.

## The general form

Every number inside an untrusted file is a claim by its author. Use those
claims to decide whether the file is *self-consistent*. Never use them to
decide how much work you are willing to do.
