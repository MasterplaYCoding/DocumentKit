package io.github.masterplaycoding.documentkit.io

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

/**
 * Supplies an asset's bytes.
 *
 * This is a stream factory rather than a `ByteArray` on purpose. Saving a
 * document with a 200 MB video attached should cost a 64 KB buffer, not 200 MB
 * of heap, and an application should not have to load an asset it merely wants
 * to carry across a save.
 *
 * [openStream] may be called more than once during a single save - once to
 * measure and digest, once to write - so it must return a fresh stream each
 * time and must not consume a one-shot source.
 */
public fun interface AssetSource {
    public fun openStream(): InputStream

    public companion object {
        /** An asset already in memory. Convenient for small assets and tests. */
        public fun ofBytes(bytes: ByteArray): AssetSource {
            val copy = bytes.copyOf()
            return AssetSource { ByteArrayInputStream(copy) }
        }

        /** An asset read from a local file each time it is needed. */
        public fun ofFile(file: File): AssetSource = AssetSource { file.inputStream() }
    }
}
