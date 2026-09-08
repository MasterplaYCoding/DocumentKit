package io.github.masterplaycoding.documentkit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class JsonDepthTest {

    @Test
    fun allowsOrdinaryNesting() {
        assertNull(JsonDepth.exceeds("""{"a":{"b":[1,2,{"c":true}]}}""", maxDepth = 8))
    }

    @Test
    fun reportsDeepNestingBeforeTheParserSeesIt() {
        val deep = "[".repeat(500) + "]".repeat(500)

        assertEquals(9, JsonDepth.exceeds(deep, maxDepth = 8))
    }

    @Test
    fun isNotFooledByBracesInsideStrings() {
        // A document full of "{{{{" in its text is legitimate, and rejecting
        // it would be a bug that only shows up on real user content.
        val text = """{"note":"${"{".repeat(400)}"}"""

        assertNull(JsonDepth.exceeds(text, maxDepth = 8))
    }

    @Test
    fun isNotFooledByEscapedQuotes() {
        val text = """{"note":"he said \"${"[".repeat(400)}\" loudly"}"""

        assertNull(JsonDepth.exceeds(text, maxDepth = 8))
    }

    @Test
    fun isNotFooledByAnEscapedBackslashBeforeAQuote() {
        // The backslash escapes itself, so the following quote does close the
        // string, and the brackets after it really are nesting.
        val text = """{"note":"ends with a backslash \\"${",".repeat(0)}${"[".repeat(20)}"""

        assertNotNull(JsonDepth.exceeds(text, maxDepth = 4))
    }

    @Test
    fun countsDepthNotTotalBrackets() {
        val wide = "[" + List(1_000) { "[1]" }.joinToString(",") + "]"

        assertNull(JsonDepth.exceeds(wide, maxDepth = 4))
    }
}
