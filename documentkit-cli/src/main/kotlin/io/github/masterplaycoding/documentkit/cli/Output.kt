package io.github.masterplaycoding.documentkit.cli

import io.github.masterplaycoding.documentkit.DocumentError
import io.github.masterplaycoding.documentkit.io.DocumentSummary
import io.github.masterplaycoding.documentkit.io.ValidationReport
import java.io.File

/** Human-readable output. */
internal object Text {

    fun summary(summary: DocumentSummary, file: File): String = buildString {
        appendLine(file.name)
        appendLine("  container version   ${summary.containerVersion}")
        appendLine("  application         ${summary.applicationId}")
        appendLine("  schema version      ${summary.schemaVersion}")
        appendLine("  document id         ${summary.documentId}")
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
                val mediaType = asset.mediaType?.let { " · $it" } ?: ""
                appendLine("    ${asset.id.value}  ${bytes(asset.length)}$mediaType")
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
            appendLine("${file.name} — ${summary.applicationId} schema ${summary.schemaVersion}")
        } else {
            appendLine(file.name)
        }

        for (entry in report.verifiedEntries) {
            appendLine("  ✓ $entry")
        }
        for (error in report.errors) {
            appendLine("  ✗ ${error.code}: ${error.detail}")
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
                else -> if (character < ' ') {
                    append("\\u%04x".format(character.code))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }
}
