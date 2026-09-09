package io.github.masterplaycoding.documentkit.samples.lantr

import io.github.masterplaycoding.documentkit.DocumentException
import io.github.masterplaycoding.documentkit.io.DocumentStore
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
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
 */
public fun main(args: Array<String>) {
    val out = PrintStream(FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8)
    val err = PrintStream(FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8)

    if (args.size != 2) {
        err.println("usage: lantr-import <source.ltrn> <destination.dkit>")
        exitProcess(2)
    }

    val source = File(args[0])
    val destination = File(args[1])

    if (!source.isFile) {
        err.println("not a file: ${source.path}")
        exitProcess(2)
    }
    if (destination.absolutePath == source.absolutePath) {
        // The importer only reads the source, and this makes that impossible
        // to get wrong by mistyping one path.
        err.println("refusing to write over the source file")
        exitProcess(2)
    }

    val report = try {
        LtrnImporter.import(source)
    } catch (cause: Exception) {
        err.println("could not read ${source.name}: ${cause.message}")
        exitProcess(1)
    }

    try {
        runBlocking {
            DocumentStore().save(
                destination = destination,
                document = report.presentation,
                documentId = source.nameWithoutExtension,
                codec = importedPresentationCodec,
                assets = report.assets,
            )
        }
    } catch (cause: DocumentException) {
        err.println("could not write ${destination.name}: ${cause.error.detail}")
        exitProcess(1)
    }

    val slides = report.presentation.sections.sumOf { it.slides.size }
    out.println(
        "imported ${report.presentation.sections.size} sections, $slides slides, " +
            "${report.assets.size} images into ${destination.name}",
    )

    if (report.unsupported.isEmpty()) {
        exitProcess(0)
    }

    // Printed in full, not summarised. The whole value of this tool over a
    // hand-written conversion is that it says what it left behind.
    out.println()
    out.println("not carried across (${report.unsupported.size}):")
    for ((where, what) in report.unsupported.distinct().sortedBy { it.where }) {
        out.println("  $where — $what")
    }
    exitProcess(0)
}
