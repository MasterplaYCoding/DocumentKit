package io.github.masterplaycoding.documentkit

/**
 * A nesting-depth pre-scan for untrusted JSON.
 *
 * `kotlinx.serialization`'s parser is recursive, so deeply nested input can
 * exhaust the stack before any of the library's own validation gets to run.
 * A `StackOverflowError` is not something an application can sensibly handle,
 * and on some runtimes it leaves the process in a doubtful state.
 *
 * This scan is a single non-recursive pass over the bytes that answers one
 * question - is this deeper than we allow - and it runs *before* parsing. It
 * is not a JSON validator: malformed input is the parser's business, and this
 * pass only has to avoid being fooled about depth. It therefore tracks string
 * literals and escapes, since a `{` inside a string is not nesting.
 */
public object JsonDepth {

    /**
     * Returns null when [text] nests no deeper than [maxDepth], or the depth
     * at which the limit was first exceeded.
     */
    public fun exceeds(text: String, maxDepth: Int): Int? {
        require(maxDepth > 0) { "maxDepth must be positive" }

        var depth = 0
        var inString = false
        var escaped = false

        for (character in text) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> inString = false
                }
                continue
            }

            when (character) {
                '"' -> inString = true
                '{', '[' -> {
                    depth += 1
                    if (depth > maxDepth) {
                        return depth
                    }
                }
                '}', ']' -> if (depth > 0) depth -= 1
            }
        }

        return null
    }
}
