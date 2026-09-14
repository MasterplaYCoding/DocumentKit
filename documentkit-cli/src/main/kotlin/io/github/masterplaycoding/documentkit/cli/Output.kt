package io.github.masterplaycoding.documentkit.cli

import io.github.masterplaycoding.documentkit.DocumentError
import io.github.masterplaycoding.documentkit.io.DocumentSummary
import io.github.masterplaycoding.documentkit.io.ValidationReport
import java.io.File

/**
 * [value] with every character a terminal would act on, or a reader could not
 * see, replaced by a `\uXXXX` escape.
 *
 * Nearly every string this tool prints came out of the file it was pointed
 * at, and the files it is pointed at are the ones someone sent you. The
 * manifest only requires application and document ids to be non-blank, media
 * types are unconstrained, and an entry name is whatever the archive's author
 * wrote. Printed raw, an ESC or a C1 CSI in a document id is an instruction to
 * the terminal - move the cursor, rewrite the line that said ✗, retitle the
 * window - and a right-to-left override makes one entry name read as another.
 *
 * Escaping rather than dropping keeps the evidence: a reader who sees \u001b
 * in a document id has learned the most important thing about that file.
 * Invisible formatting characters are escaped too, zero-width joiners
 * included, so an emoji sequence in an id prints in pieces. For a tool whose
 * job is to show what a file really contains, that is the right trade.
 */
internal fun visible(value: String): String {
    if (value.none(::isUnprintable)) return value
    return buildString {
        for (character in value) {
            if (isUnprintable(character)) append("\\u%04x".format(character.code)) else append(character)
        }
    }
}

/** C0 and C1 controls, DEL, and invisible formatting and separator characters. */
internal fun isUnprintable(character: Char): Boolean = when (Character.getType(character)) {
    Character.CONTROL.toInt(),
    Character.FORMAT.toInt(),
    Character.LINE_SEPARATOR.toInt(),
    Character.PARAGRAPH_SEPARATOR.toInt(),
    -> true
    else -> false
}

/** Human-readable output. Every value that came from outside passes through [visible]. */
internal object Text {

    fun summary(summary: DocumentSummary, file: File): String = buildString {
        appendLine(visible(file.name))
        appendLine("  container version   ${summary.containerVersion}")
        appendLine("  application         ${visible(summary.applicationId)}")
        appendLine("  schema version      ${summary.schemaVersion}")
        appendLine("  document id         ${visible(summary.documentId)}")
        appendLine("  archive size        ${bytes(summary.archiveBytes)}")
        appendLine("  entries             ${summary.entryCount}")
        appendLine("  document.json       ${bytes(summary.documentLength)} (declared)")

        if (summary.assets.isEmpty()) {
            appendLine("  assets              none")
        } else {
            appendLine("  assets              ${summary.assets.size}, ${bytes(
                summary.assets.sumOf { it.length },
            )} declared")
            for (asset in summary.assets) {
                val mediaType = asset.mediaType?.let { " · ${visible(it)}" } ?: ""
                appendLine("    ${visible(asset.id.value)}  ${bytes(asset.length)}$mediaType")
            }
        }

        // "declared" appears throughout on purpose: inspect reads the
        // manifest's claims and verifies none of them. That is what validate
        // is for, and conflating the two is exactly the mistake this format's
        // limits exist to avoid.
        append("\nSizes above are declared, not verified. Run `validate` to check them.")
    }

    fun report(report: ValidationReport, file: File): String = buildString {
        val summary = report.summary
        if (summary != null) {
            appendLine("${visible(file.name)} — ${visible(summary.applicationId)} schema ${summary.schemaVersion}")
        } else {
            appendLine(visible(file.name))
        }

        for (entry in report.verifiedEntries) {
            appendLine("  ✓ ${visible(entry)}")
        }
        for (error in report.errors) {
            appendLine("  ✗ ${error.code}: ${visible(error.detail)}")
        }

        appendLine()
        if (report.isValid) {
            appendLine("valid — ${report.verifiedEntries.size} entries matched their declared length and digest")
        } else {
            appendLine("invalid — ${report.errors.size} problem${if (report.errors.size == 1) "" else "s"}")
        }
        append("checked: ${report.scope}")
    }

    private fun bytes(value: Long): String = when {
        value < 1024 -> "$value B"
        value < 1024 * 1024 -> "${value / 1024} KiB"
        else -> "${value / (1024 * 1024)} MiB"
    }
}

/**
 * Machine-readable output.
 *
 * Written by hand rather than with a serializer: the shape is small, fixed,
 * and part of this tool's contract with whatever parses it, so it should be
 * visible here rather than derived from types that may change for unrelated
 * reasons.
 */
internal object Json {

    fun summary(summary: DocumentSummary): String = buildString {
        append("{")
        append(""""ok":true,""")
        append(""""containerVersion":${summary.containerVersion},""")
        append(""""applicationId":${quote(summary.applicationId)},""")
        append(""""schemaVersion":${summary.schemaVersion},""")
        append(""""documentId":${quote(summary.documentId)},""")
        append(""""archiveBytes":${summary.archiveBytes},""")
        append(""""entryCount":${summary.entryCount},""")
        append(""""documentLength":${summary.documentLength},""")
        append(""""declaredContentBytes":${summary.declaredContentBytes},""")
        append(""""verified":false,""")
        append(""""assets":[""")
        append(
            summary.assets.joinToString(",") { asset ->
                """{"id":${quote(asset.id.value)},"length":${asset.length},""" +
                    """"sha256":${quote(asset.sha256)},"mediaType":${
                        asset.mediaType?.let(::quote) ?: "null"
                    }}"""
            },
        )
        append("]}")
    }

    fun report(report: ValidationReport): String = buildString {
        append("{")
        append(""""ok":${report.isValid},""")
        append(""""applicationId":${report.summary?.applicationId?.let(::quote) ?: "null"},""")
        append(""""schemaVersion":${report.summary?.schemaVersion ?: "null"},""")
        append(""""scope":${quote(report.scope)},""")
        append(""""verifiedEntries":[""")
        append(report.verifiedEntries.joinToString(",") { quote(it) })
        append("""],"errors":[""")
        append(
            report.errors.joinToString(",") { error ->
                """{"code":${quote(error.code)},"detail":${quote(error.detail)}}"""
            },
        )
        append("]}")
    }

    fun error(error: DocumentError): String =
        """{"ok":false,"errors":[{"code":${quote(error.code)},"detail":${quote(error.detail)}}]}"""

    private fun quote(value: String): String = buildString {
        append('"')
        for (character in value) {
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                // Beyond what JSON requires: C1 controls, DEL and invisible
                // formatting characters decode to the same string either way,
                // and escaped they are also safe for someone who cats the output.
                else -> if (character < ' ' || isUnprintable(character)) {
                    append("\\u%04x".format(character.code))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }
}
