package io.github.masterplaycoding.documentkit.io

import io.github.masterplaycoding.documentkit.DocumentError
import io.github.masterplaycoding.documentkit.DocumentException
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Local filesystem operations with the guarantees stated explicitly. */
internal object LocalFiles {

    /**
     * Forces a file's contents to storage.
     *
     * This covers the file's *data*. It does not fsync the containing
     * directory, so the guarantee stops short of "this file will still be here
     * after a power cut". Java offers no portable directory sync, and claiming
     * durability we cannot deliver would be worse than documenting the limit.
     */
    fun forceToStorage(file: File) {
        try {
            RandomAccessFile(file, "rw").use { handle -> handle.channel.force(true) }
        } catch (cause: Exception) {
            throw DocumentException(
                DocumentError.IoFailure("flush", cause.message ?: "could not force file to storage"),
            )
        }
    }

    /**
     * Moves [source] over [destination] atomically, or fails.
     *
     * There is no fallback to a plain copy. An atomic move is the difference
     * between "the user has either the old document or the new one" and "the
     * user has half a file"; downgrading silently would hand back the second
     * while the caller believed they had the first.
     *
     * The most common real cause is a cross-filesystem move, which is why
     * saving stages the new archive as a *sibling* of the destination.
     */
    fun atomicReplace(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (cause: AtomicMoveNotSupportedException) {
            throw DocumentException(
                DocumentError.AtomicReplaceUnsupported(
                    destination.absolutePath,
                    cause.reason ?: "the filesystem does not support atomic moves",
                ),
            )
        } catch (cause: Exception) {
            // On Windows a move onto a file another handle still has open
            // fails here. Saying so is more useful than a bare IOException.
            throw DocumentException(
                DocumentError.AtomicReplaceUnsupported(
                    destination.absolutePath,
                    cause.message ?: "the move was refused; is the destination open elsewhere?",
                ),
            )
        }
    }
}
