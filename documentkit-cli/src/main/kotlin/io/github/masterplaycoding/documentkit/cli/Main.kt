package io.github.masterplaycoding.documentkit.cli

import io.github.masterplaycoding.documentkit.DocumentException
import io.github.masterplaycoding.documentkit.DocumentLimits
import io.github.masterplaycoding.documentkit.io.DocumentStore
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking

/**
 * `documentkit inspect` and `documentkit validate`.
 *
 * Exit codes are the interface, because this is a tool CI will run:
 *
 * - `0` — the document is valid, or was inspected successfully.
 * - `1` — the document is invalid. A finding about the *file*.
 * - `2` — the invocation or the environment was wrong: bad arguments, a
 *   missing path, an unreadable file. A finding about the *call*.
 *
 * Keeping those apart matters. A build that treats "your document is corrupt"
 * and "you typed the wrong flag" as the same failure teaches people to ignore
 * both.
 */
public fun main(args: Array<String>) {
    // Java picks the platform's legacy code page for System.out, which on
    // Windows turns this tool's ✓ and ✗ into question marks. The output is
    // UTF-8 whatever the console's default is, and JSON output would otherwise
    // be corrupted for any non-ASCII document id.
    val out = PrintStream(FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8)
    val err = PrintStream(FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8)

    exitProcess(run(args, out::println, err::println))
}

internal fun run(args: Array<String>, out: (String) -> Unit, err: (String) -> Unit): Int {
    val parsed = try {
        parseArguments(args)
    } catch (cause: UsageError) {
        err(cause.message ?: "invalid arguments")
        err("")
        err(USAGE)
        return EXIT_USAGE
    }

    if (parsed.help) {
        out(USAGE)
        return EXIT_OK
    }

    val file = File(parsed.path)
    if (!file.isFile) {
        err("not a file: ${parsed.path}")
        return EXIT_USAGE
    }

    val store = DocumentStore(parsed.limits)

    return try {
        runBlocking {
            when (parsed.command) {
                Command.INSPECT -> inspect(store, file, parsed.json, out, err)
                Command.VALIDATE -> validate(store, file, parsed.json, out)
            }
        }
    } catch (cause: Exception) {
        // Anything that is not a DocumentError is an environment problem -
        // permissions, a vanished file - not a verdict on the document.
        err("could not read ${parsed.path}: ${cause.message ?: cause::class.simpleName}")
        EXIT_USAGE
    }
}

private suspend fun inspect(
    store: DocumentStore,
    file: File,
    json: Boolean,
    out: (String) -> Unit,
    err: (String) -> Unit,
): Int {
    val summary = try {
        store.inspect(file)
    } catch (cause: DocumentException) {
        // inspect answers "what is this file?", and being unable to answer is
        // a fact about the document.
        if (json) out(Json.error(cause.error)) else err("${cause.error.code}: ${cause.error.detail}")
        return EXIT_INVALID
    }

    out(if (json) Json.summary(summary) else Text.summary(summary, file))
    return EXIT_OK
}

private suspend fun validate(
    store: DocumentStore,
    file: File,
    json: Boolean,
    out: (String) -> Unit,
): Int {
    val report = store.validate(file)
    out(if (json) Json.report(report) else Text.report(report, file))
    return if (report.isValid) EXIT_OK else EXIT_INVALID
}

internal const val EXIT_OK = 0
internal const val EXIT_INVALID = 1
internal const val EXIT_USAGE = 2

internal enum class Command { INSPECT, VALIDATE }

internal data class Arguments(
    val command: Command,
    val path: String,
    val json: Boolean,
    val limits: DocumentLimits,
    val help: Boolean = false,
)

internal class UsageError(message: String) : Exception(message)

internal fun parseArguments(args: Array<String>): Arguments {
    if (args.isEmpty() || args[0] == "--help" || args[0] == "-h") {
        return Arguments(Command.INSPECT, "", json = false, limits = DocumentLimits.Default, help = true)
    }

    val command = when (args[0]) {
        "inspect" -> Command.INSPECT
        "validate" -> Command.VALIDATE
        else -> throw UsageError("unknown command '${args[0]}'")
    }

    var json = false
    var maxArchiveBytes: Long? = null
    val paths = mutableListOf<String>()

    var index = 1
    while (index < args.size) {
        when (val argument = args[index]) {
            "--json" -> json = true
            "--max-archive-bytes" -> {
                index += 1
                val value = args.getOrNull(index)
                    ?: throw UsageError("--max-archive-bytes needs a value")
                maxArchiveBytes = value.toLongOrNull()?.takeIf { it > 0 }
                    ?: throw UsageError("--max-archive-bytes must be a positive integer")
            }
            else -> {
                if (argument.startsWith("-")) throw UsageError("unknown option '$argument'")
                paths += argument
            }
        }
        index += 1
    }

    val path = paths.singleOrNull()
        ?: throw UsageError(
            if (paths.isEmpty()) "expected a path to a document" else "expected exactly one path",
        )

    return Arguments(
        command = command,
        path = path,
        json = json,
        limits = maxArchiveBytes?.let { DocumentLimits.Default.copy(maxArchiveBytes = it) }
            ?: DocumentLimits.Default,
    )
}

internal val USAGE: String = """
    documentkit — inspect and validate DocumentKit containers

    Usage:
      documentkit inspect  <file> [--json]
      documentkit validate <file> [--json]

    Commands:
      inspect    Report what the container says about itself. Reads the
                 manifest only; does not verify content.
      validate   Stream every declared entry and check actual lengths and
                 SHA-256 digests against the manifest.

    Options:
      --json                     Machine-readable output, for CI.
      --max-archive-bytes <n>    Raise or lower the archive size limit.
      -h, --help                 Show this message.

    Exit codes:
      0  valid, or inspected successfully
      1  the document is invalid
      2  bad arguments, or the file could not be read

    Note: without an application's codec this tool checks the container only.
    It cannot tell you whether document.json matches an application's schema,
    whether a migration chain reaches it, or whether the model is semantically
    valid.
""".trimIndent()
