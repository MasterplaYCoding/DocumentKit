package io.github.masterplaycoding.documentkit.io

/**
 * What a completed save actually did.
 *
 * A receipt names the guarantee that was achieved, not the one that was
 * requested. "It saved" is not enough information to decide whether the
 * previous version of a user's document is still recoverable.
 */
public sealed class SaveReceipt {
    /** Total bytes written to the destination. */
    public abstract val bytesWritten: Long

    /**
     * The destination was replaced atomically: any reader saw either the old
     * document or the new one, never a partial file.
     *
     * The guarantee is atomic *visibility* on this filesystem. It is not a
     * claim about power loss, about directory metadata reaching the disk, or
     * about network filesystems, none of which the library can promise.
     */
    public data class AtomicReplace(
        val path: String,
        override val bytesWritten: Long,
    ) : SaveReceipt()

    /**
     * The archive was written through a content provider's output stream.
     *
     * The complete document is built and verified privately first, so what is
     * copied out is always a valid archive. The copy itself is not atomic:
     * the provider owns the destination and does not offer replacement, so an
     * interruption mid-copy can leave the destination partially written. This
     * is why the Android sample offers "Save a copy" rather than advertising
     * crash-safe overwrite.
     */
    public data class ProviderManagedExport(
        val uri: String,
        override val bytesWritten: Long,
    ) : SaveReceipt()
}
