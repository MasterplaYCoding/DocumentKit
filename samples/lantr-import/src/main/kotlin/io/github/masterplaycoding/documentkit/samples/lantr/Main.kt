package io.github.masterplaycoding.documentkit.samples.lantr

import io.github.masterplaycoding.documentkit.DocumentException
import io.github.masterplaycoding.documentkit.io.DocumentStore
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking

/**
 * `lantr-import <source.ltrn> <destination.dkit>`
 *
 * Reads a Lantr archive and writes a DocumentKit container. The source is
 * opened read-only and never written to; the destination is a separate file
 * the caller names.
 *
 * Exit codes follow documentkit-cli: `0` imported, `1` the source could not be
 * imported or the result could not be written, `2` bad arguments or a file
 * that could not be read at all.
 */
public fun main(args: Array<String>) {
    val out = PrintStream(FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8)
    val err = PrintStream(FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8)

    exitProcess(run(args, out::println, err::println))
}

internal fun run(args: Array<String>, out: (String) -> Unit, err: (String) -> Unit): Int {
    if (args.size != 2) {
        err("usage: lantr-import <source.ltrn> <destination.dkit>")
        return 2
    }

    val source = File(args[0])
    val destination = File(args[1])

    if (!source.isFile) {
        err("not a file: ${visible(source.path)}")
        return 2
    }
    if (destination.absoluteFile.normalize() == source.absoluteFile.normalize()) {
        // The importer only reads the source, and this makes that impossible
        // to get wrong by mistyping one path.
        err("refusing to write over the source file")
        return 2
    }

    val report = try {
        LtrnImporter.import(source)
    } catch (refused: LtrnImportException) {
        err("could not import ${visible(source.name)}: ${visible(refused.message.orEmpty())}")
        return 1
    } catch (failure: IOException) {
        err("could not read ${visible(source.name)}: ${visible(failure.message ?: failure::class.simpleName.orEmpty())}")
        return 2
    }

    try {
        runBlocking {
            DocumentStore().save(
                destination = destination,
                document = report.presentation,
                // A container's id must not be blank, and a file called `.ltrn`
                // has no name to give it.
                documentId = source.nameWithoutExtension.ifBlank { "imported" },
                codec = importedPresentationCodec,
                assets = report.assets,
            )
        }
    } catch (cause: DocumentException) {
        err("could not write ${visible(destination.name)}: ${visible(cause.error.detail)}")
        return 1
    }

    val slides = report.presentation.sections.sumOf { it.slides.size }
    out(
        "imported ${report.presentation.sections.size} sections, $slides slides, " +
            "${report.assets.size} images into ${visible(destination.name)}",
    )

    if (report.unsupported.isEmpty()) return 0

    // Printed in full, not summarised. The whole value of this tool over a
    // hand-written conversion is that it says what it left behind.
    out("")
    out("not carried across (${report.unsupported.size}):")
    for ((where, what) in report.unsupported.distinct().sortedBy { it.where }) {
        out("  ${visible(where)} — ${visible(what)}")
    }
    return 0
}

/**
 * [value] with control, invisible-formatting and separator characters written
 * as `\uXXXX` escapes.
 *
 * Entry names, image paths and block types come from the `.ltrn`, which is
 * somebody else's file. Printed raw, an escape sequence in one is an
 * instruction to the terminal. This is documentkit-cli's rule, repeated
 * because the sample does not depend on that module.
 */
private fun visible(value: String): String = buildString {
    for (character in value) {
        when (Character.getType(character)) {
            Character.CONTROL.toInt(),
            Character.FORMAT.toInt(),
            Character.LINE_SEPARATOR.toInt(),
            Character.PARAGRAPH_SEPARATOR.toInt(),
            -> append("\\u%04x".format(character.code))
            else -> append(character)
        }
    }
}
