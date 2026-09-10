package io.github.masterplaycoding.documentkit

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable

/**
 * [DocumentCodec.encode] produces the bytes every saved document is made of,
 * and the manifest stores a SHA-256 over them. Two properties follow from that
 * and neither had a test.
 */
class DocumentEncodingTest {

    @Serializable
    private data class Journal(
        val title: String,
        val entries: List<String> = emptyList(),
        val archived: Boolean = false,
        val revision: Int = 1,
    )

    private val codec = DocumentCodec(
        applicationId = "example.journal",
        schemaVersion = 1,
        serializer = Journal.serializer(),
    )

    private fun decode(bytes: ByteArray): Journal {
        val body = codec.parseBody(bytes).getOrThrow()
        return assertIs<DecodeResult.Success<Journal>>(codec.decode(body, 1)).document
    }

    // --- determinism --------------------------------------------------------

    @Test
    fun encodingTheSameDocumentTwiceProducesIdenticalBytes() {
        // The manifest carries a digest over these bytes, and the format
        // deliberately holds no timestamp so that two saves of identical
        // content are identical files. That promise is worth exactly as much
        // as this property: if encoding varied, every save would produce a new
        // digest and content-addressed storage, deduplication and "has this
        // actually changed?" would all quietly stop working.
        val document = Journal("Field notes", listOf("one", "two"))

        assertContentEquals(codec.encode(document), codec.encode(document))
    }

    @Test
    fun twoEqualDocumentsEncodeIdentically() {
        // Equal by value, constructed separately. Encoding must not depend on
        // identity or on anything the instance happens to carry.
        val first = Journal("Field notes", listOf("one", "two"))
        val second = Journal("Field notes", listOf("one", "two"))

        assertContentEquals(codec.encode(first), codec.encode(second))
    }

    @Test
    fun fieldOrderFollowsTheModelRatherThanTheData() {
        // Declaration order, not insertion or hash order. A codec that emitted
        // fields in a varying order would break the digest without changing
        // the document.
        val text = codec.encode(Journal("A")).decodeToString()

        assertTrue(
            text.indexOf("\"title\"") < text.indexOf("\"entries\""),
            "fields should appear in declaration order: $text",
        )
        assertTrue(text.indexOf("\"entries\"") < text.indexOf("\"archived\""))
    }

    // --- defaults -----------------------------------------------------------

    @Test
    fun defaultsAreWrittenOutRatherThanOmitted() {
        // encodeDefaults is on, deliberately. An omitted field means "whatever
        // this build's default happens to be", so a document saved today and
        // opened by a build that changed the default would come back as a
        // different document - silently, with no migration and no error. Every
        // field being present makes a document mean the same thing forever.
        val text = codec.encode(Journal("A")).decodeToString()

        assertContains(text, "\"archived\"")
        assertContains(text, "\"revision\"")
        assertContains(text, "\"entries\"")
    }

    @Test
    fun aDocumentAtItsDefaultsStillRoundTrips() {
        val document = Journal("A")

        assertEquals(document, decode(codec.encode(document)))
    }

    // --- round trip ---------------------------------------------------------

    @Test
    fun encodeThenDecodeReturnsTheSameModel() {
        val document = Journal("Field notes", listOf("one", "two"), archived = true, revision = 7)

        assertEquals(document, decode(codec.encode(document)))
    }

    @Test
    fun survivesTextThatIsNotAscii() {
        // encodeToByteArray is UTF-8. An emoji is four bytes and a combining
        // sequence is several, so a length computed in characters rather than
        // bytes would corrupt exactly here - and the manifest's length is in
        // bytes.
        val document = Journal("Ünïcödé 🎉 中文", listOf("日本語", "café́"))
        val encoded = codec.encode(document)

        assertEquals(document, decode(encoded))
        assertTrue(encoded.size > document.title.length, "UTF-8 should be wider than the char count")
    }

    @Test
    fun survivesTextThatLooksLikeJson() {
        // Quotes, braces and backslashes in content must be escaped rather
        // than reinterpreted on the way back in.
        val document = Journal("{\"not\":\"a document\"}", listOf("back\\slash", "quote\"inside"))

        assertEquals(document, decode(codec.encode(document)))
    }

    @Test
    fun survivesAnEmptyDocument() {
        assertEquals(Journal(""), decode(codec.encode(Journal(""))))
    }

    // --- parseBody ----------------------------------------------------------

    @Test
    fun parseBodyRejectsAnythingThatIsNotAnObject() {
        for (body in listOf("[]", "\"text\"", "42", "true", "null")) {
            assertTrue(
                codec.parseBody(body.encodeToByteArray()).isFailure,
                "should have refused a top-level $body",
            )
        }
    }

    @Test
    fun parseBodyRejectsMalformedJson() {
        for (body in listOf("", "{", "{\"title\":}", "not json at all")) {
            assertTrue(codec.parseBody(body.encodeToByteArray()).isFailure, "should have refused: $body")
        }
    }

    @Test
    fun parseBodyAcceptsWhatEncodeProduces() {
        // The pair has to compose, and nothing else in the suite says so.
        assertTrue(codec.parseBody(codec.encode(Journal("A"))).isSuccess)
    }

    // --- construction -------------------------------------------------------

    @Test
    fun refusesACodecThatCannotIdentifyItself() {
        // A blank application id makes every document indistinguishable from
        // every other application's, which the container format relies on to
        // refuse a foreign file.
        for (id in listOf("", " ", "\t")) {
            assertTrue(
                runCatching {
                    DocumentCodec(id, 1, Journal.serializer())
                }.exceptionOrNull() is IllegalArgumentException,
                "should have refused application id '$id'",
            )
        }
    }

    @Test
    fun refusesANonPositiveSchemaVersion() {
        for (version in listOf(0, -1)) {
            assertTrue(
                runCatching {
                    DocumentCodec("example.journal", version, Journal.serializer())
                }.exceptionOrNull() is IllegalArgumentException,
                "should have refused schema version $version",
            )
        }
    }
}
