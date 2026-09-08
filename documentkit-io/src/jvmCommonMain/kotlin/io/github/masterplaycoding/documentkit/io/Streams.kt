package io.github.masterplaycoding.documentkit.io

import io.github.masterplaycoding.documentkit.DocumentError
import io.github.masterplaycoding.documentkit.DocumentException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Copy buffer size. Large enough to be efficient, small enough to be cheap. */
internal const val COPY_BUFFER_BYTES = 64 * 1024

/**
 * Streams [this] into [target], counting and digesting as it goes, and fails
 * as soon as [limit] bytes have been exceeded.
 *
 * The counting is the point. A ZIP entry's header says how large it claims to
 * be, and the archive is untrusted input, so its own claims cannot be the
 * protection against it being too large. Reading stops at the limit rather
 * than after discovering the limit was passed, so a decompression bomb costs
 * the limit, not the bomb.
 *
 * @return the byte count and the lowercase hex SHA-256 of what was read.
 */
internal fun InputStream.copyMeasured(
    target: OutputStream?,
    limit: Long,
    limitName: String,
): MeasuredCopy {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(COPY_BUFFER_BYTES)
    var total = 0L

    while (true) {
        val read = read(buffer)
        if (read < 0) break

        total += read
        if (total > limit) {
            throw DocumentException(DocumentError.LimitExceeded(limitName, limit))
        }

        digest.update(buffer, 0, read)
        target?.write(buffer, 0, read)
    }

    return MeasuredCopy(length = total, sha256 = digest.digest().toHexString())
}

internal data class MeasuredCopy(val length: Long, val sha256: String)

internal fun ByteArray.toHexString(): String {
    val hex = StringBuilder(size * 2)
    for (byte in this) {
        val value = byte.toInt() and 0xFF
        hex.append(HEX[value ushr 4])
        hex.append(HEX[value and 0x0F])
    }
    return hex.toString()
}

internal fun ByteArray.sha256(): String =
    MessageDigest.getInstance("SHA-256").digest(this).toHexString()

private const val HEX = "0123456789abcdef"

/**
 * Reads at most [limit] bytes into memory, failing if there are more.
 *
 * Only used for entries the format caps at a small size - the manifest and the
 * document body. Assets are never loaded whole: that is the difference between
 * opening a document with a 200 MB video in it and running out of heap.
 */
internal fun InputStream.readAtMost(limit: Long, limitName: String): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    copyMeasured(output, limit, limitName)
    return output.toByteArray()
}
