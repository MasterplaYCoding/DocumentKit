package io.github.masterplaycoding.documentkit.io

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32

/**
 * A minimal ZIP writer that emits exactly what it is told to.
 *
 * `java.util.zip.ZipOutputStream` refuses to write two entries with the same
 * name, which is convenient for ordinary use and useless for testing that a
 * *reader* rejects such an archive. Nothing stops an attacker, or a buggy
 * writer in another language, from producing one.
 *
 * Entries are stored uncompressed, which keeps this to the two structures that
 * matter: local file headers and the central directory.
 */
class RawZip {

    private data class Entry(val name: ByteArray, val data: ByteArray, val crc: Long)

    private val entries = mutableListOf<Entry>()

    fun entry(name: String, data: ByteArray): RawZip = apply {
        val crc = CRC32().apply { update(data) }.value
        entries += Entry(name.toByteArray(Charsets.UTF_8), data, crc)
    }

    fun entry(name: String, text: String): RawZip = entry(name, text.toByteArray())

    fun writeTo(file: File): File {
        val output = ByteArrayOutputStream()
        val offsets = mutableListOf<Int>()

        for (entry in entries) {
            offsets += output.size()
            output.writeInt(0x04034b50) // local file header signature
            output.writeShort(20) // version needed
            output.writeShort(0) // flags
            output.writeShort(0) // stored, no compression
            output.writeShort(0) // modification time
            output.writeShort(0) // modification date
            output.writeInt(entry.crc.toInt())
            output.writeInt(entry.data.size)
            output.writeInt(entry.data.size)
            output.writeShort(entry.name.size)
            output.writeShort(0) // extra field length
            output.write(entry.name)
            output.write(entry.data)
        }

        val centralDirectoryStart = output.size()
        for ((index, entry) in entries.withIndex()) {
            output.writeInt(0x02014b50) // central directory header signature
            output.writeShort(20) // version made by
            output.writeShort(20) // version needed
            output.writeShort(0) // flags
            output.writeShort(0) // stored
            output.writeShort(0) // modification time
            output.writeShort(0) // modification date
            output.writeInt(entry.crc.toInt())
            output.writeInt(entry.data.size)
            output.writeInt(entry.data.size)
            output.writeShort(entry.name.size)
            output.writeShort(0) // extra field length
            output.writeShort(0) // comment length
            output.writeShort(0) // disk number
            output.writeShort(0) // internal attributes
            output.writeInt(0) // external attributes
            output.writeInt(offsets[index])
            output.write(entry.name)
        }
        val centralDirectorySize = output.size() - centralDirectoryStart

        output.writeInt(0x06054b50) // end of central directory signature
        output.writeShort(0) // disk number
        output.writeShort(0) // disk with central directory
        output.writeShort(entries.size)
        output.writeShort(entries.size)
        output.writeInt(centralDirectorySize)
        output.writeInt(centralDirectoryStart)
        output.writeShort(0) // comment length

        file.writeBytes(output.toByteArray())
        return file
    }

    private fun ByteArrayOutputStream.writeShort(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeInt(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 24) and 0xFF)
    }
}
