package io.github.masterplaycoding.documentkit.io

import io.github.masterplaycoding.documentkit.DocumentError
import io.github.masterplaycoding.documentkit.DocumentException
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * Opens [entry] so that damage discovered while reading it is a
 * [DocumentException], like every other kind of damage.
 *
 * `java.util.zip` lists entries from the central directory, and only looks at
 * an entry's *local* header when the entry's bytes are first read. A file
 * whose directory is intact and whose local header is not therefore lists
 * perfectly, passes every structural check, and then throws `ZipException` -
 * or `EOFException`, when the header points past the end of the file - from
 * the middle of a read. Every read of archive bytes goes through here so that
 * none of them can let one escape. Found by [FuzzTest]'s first run.
 *
 * Structural damage becomes [DocumentError.InvalidEntry], naming the entry. A
 * plain `IOException` - the disk, not the file - stays an
 * [DocumentError.IoFailure]: calling a failing device "a damaged document"
 * would send the user looking in the wrong place.
 */
internal fun ZipFile.openEntry(entry: ZipEntry): InputStream =
    EntryInputStream(entry.name, translating(entry.name) { getInputStream(entry) })

private class EntryInputStream(
    private val name: String,
    private val delegate: InputStream,
) : InputStream() {
    override fun read(): Int = translating(name) { delegate.read() }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        translating(name) { delegate.read(buffer, offset, length) }

    override fun skip(count: Long): Long = translating(name) { delegate.skip(count) }

    /**
     * Always 0, which the contract of `available` allows.
     *
     * `java.util.zip` answers with the entry's *declared* size - a header field
     * in an untrusted file. `InputStream.readBytes()` sizes its first buffer
     * from it, so a document whose content verifies perfectly but whose header
     * claims two gigabytes made `readAsset` request an array larger than the
     * VM allows. Found by the fuzz sweep; see `declared-size-huge.dkit`.
     */
    override fun available(): Int = 0

    override fun close() = translating(name) { delegate.close() }
}

private inline fun <R> translating(entry: String, block: () -> R): R =
    try {
        block()
    } catch (damaged: ZipException) {
        // java.util.zip's messages describe structure ("invalid LOC header"),
        // never content, so they are safe to carry.
        throw DocumentException(DocumentError.InvalidEntry(entry, damaged.message ?: "damaged entry"))
    } catch (truncated: EOFException) {
        throw DocumentException(DocumentError.InvalidEntry(entry, "the archive ends inside this entry"))
    } catch (failure: IOException) {
        throw DocumentException(
            DocumentError.IoFailure("read", failure.message ?: failure::class.java.simpleName),
        )
    }
