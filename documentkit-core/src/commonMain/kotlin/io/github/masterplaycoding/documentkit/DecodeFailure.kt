package io.github.masterplaycoding.documentkit

/**
 * Turns a serialization failure into a message that explains the problem
 * without reproducing the document.
 *
 * `kotlinx.serialization`'s exception messages are written for a developer
 * debugging their own data, so depending on the path they embed the offending
 * value or a slice of the raw JSON input. Those messages were being copied
 * verbatim into [DocumentError.InvalidJson], which is rendered by
 * [DocumentException]'s message and therefore lands in logs, crash reporters
 * and bug reports. The user's document is theirs.
 *
 * What a reader needs in order to act is *which* structural thing went wrong
 * and *where*. Field names are schema, not content, so they are kept; values
 * are dropped.
 *
 * This is public because [documentkit-io] needs it across a module boundary,
 * where `internal` does not reach. Application code is not expected to call it.
 */
public object DecodeFailure {

    // kotlinx phrases both unknown keys and type mismatches as "key '<name>'",
    // and missing fields as "Field '<name>'". The length cap keeps a pathological
    // name from reintroducing the leak this function exists to prevent.
    private val NAMED_KEY = Regex("""(?:key|[Ff]ield) '([^']{1,64})'""")

    /**
     * @param entry the archive entry being decoded, for context.
     * @return a description safe to log, containing no document values.
     */
    public fun describe(cause: Throwable, entry: String): String {
        val message = cause.message.orEmpty()
        val name = NAMED_KEY.find(message)?.groupValues?.get(1)

        return when {
            message.contains("unknown key", ignoreCase = true) ->
                "$entry contains an unrecognised field" + name.quoted() +
                    ". It was written by a build that understands more of this " +
                    "format than the current one."

            message.contains("is required", ignoreCase = true) ||
                message.contains("missing", ignoreCase = true) ->
                "$entry is missing a required field" + name.quoted() + "."

            name != null ->
                "$entry has a value of the wrong type for field '$name'."

            else ->
                "$entry does not match the expected schema " +
                    "(${cause::class.simpleName ?: "decoding failed"})."
        }
    }

    private fun String?.quoted(): String = if (this == null) "" else " '$this'"
}
