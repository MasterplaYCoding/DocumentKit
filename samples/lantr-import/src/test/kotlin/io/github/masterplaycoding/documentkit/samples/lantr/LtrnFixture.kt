package io.github.masterplaycoding.documentkit.samples.lantr

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds a `.ltrn` archive in the layout Lantr writes.
 *
 * Generated rather than committed, for the same reason DocumentKit's malformed
 * corpus is: a fixture you can read is worth more than a binary you cannot, and
 * nobody has to find a real presentation to run the tests.
 *
 * The shapes here were taken from Lantr's `LtrnArchiveService.buildSlideObject`,
 * `buildBlockObject` and `buildMetadataObject`. If the real format ever differs
 * from this, the importer is wrong about the real format and this fixture is
 * wrong in exactly the same way — which is the honest limit of testing a
 * converter against a reconstruction.
 */
class LtrnFixture(private val file: File) {

    private val entries = mutableListOf<Pair<String, ByteArray>>()

    fun entry(name: String, bytes: ByteArray): LtrnFixture = apply { entries += name to bytes }

    fun entry(name: String, text: String): LtrnFixture = entry(name, text.toByteArray())

    fun build(): File {
        ZipOutputStream(file.outputStream().buffered()).use { output ->
            for ((name, bytes) in entries) {
                output.putNextEntry(ZipEntry(name))
                output.write(bytes)
                output.closeEntry()
            }
        }
        return file
    }

    companion object {
        /** A presentation with two sections, three slides and one image. */
        fun complete(file: File): LtrnFixture = LtrnFixture(file)
            .entry(
                "metadata.json",
                """
                {
                  "id": "3f2a-91cc",
                  "title": "Extracting a persistence layer",
                  "author": "Matei Ursache",
                  "description": "A talk about taking one layer out of an editor.",
                  "duration": "FIFTEEN_MINUTES",
                  "created_at": "2026-03-01T10:00:00Z",
                  "modified_at": "2026-03-02T11:30:00Z",
                  "audience": { "group_size": "SMALL", "main_role": "ENGINEER" },
                  "purpose": ["INFORM", "PERSUADE"]
                }
                """.trimIndent(),
            )
            .entry(
                "theme.json",
                """{"background":{"red":1.0,"green":1.0,"blue":1.0}}""",
            )
            .entry("arc/blocks.json", """{"block_ids":["block-a","block-b"]}""")
            .entry(
                "arc/blocks/block_001.json",
                """
                {
                  "id": "block-a",
                  "name": "The problem",
                  "description": "Why the layer had to come out",
                  "main_idea": "Application code and library code have different obligations.",
                  "category": "SETUP",
                  "rhetoric": { "ethos": "MEDIUM", "logos": "HIGH", "pathos": "LOW" },
                  "block_type": { "predefined": "CONTEXT" },
                  "slide_ids": ["slide-1", "slide-2"]
                }
                """.trimIndent(),
            )
            .entry(
                "arc/blocks/block_002.json",
                """
                {
                  "id": "block-b",
                  "name": "The result",
                  "description": "What changed",
                  "main_idea": "Seven behaviours did not survive contact with arbitrary input.",
                  "category": "PAYOFF",
                  "rhetoric": { "ethos": "HIGH", "logos": "HIGH", "pathos": "MEDIUM" },
                  "block_type": { "predefined": "CONCLUSION" },
                  "slide_ids": ["slide-3"]
                }
                """.trimIndent(),
            )
            .entry(
                "slides/slide_001.json",
                """
                {
                  "id": "slide-1",
                  "content_arrangement": "top",
                  "uses_alt_scheme": false,
                  "blocks": [
                    { "block_type": "text", "id": "t1", "type": "TITLE",
                      "content_alignment": "center", "content": "Extracting a persistence layer" }
                  ]
                }
                """.trimIndent(),
            )
            .entry(
                "slides/slide_002.json",
                """
                {
                  "id": "slide-2",
                  "content_arrangement": "split",
                  "uses_alt_scheme": true,
                  "override_color": { "red": 0.1, "green": 0.1, "blue": 0.1 },
                  "blocks": [
                    { "block_type": "text", "id": "t2", "type": "BODY",
                      "content_alignment": "start", "content": "The format survived. The behaviours did not.",
                      "override_color": { "red": 0.5, "green": 0.0, "blue": 0.0 } },
                    { "block_type": "image", "id": "i1", "type": "IMAGE",
                      "content_alignment": "center", "image_height": 420.0,
                      "corner_radius": 12.0, "image_path": "diagram.png" }
                  ]
                }
                """.trimIndent(),
            )
            .entry(
                "slides/slide_003.json",
                """
                {
                  "id": "slide-3",
                  "content_arrangement": "top",
                  "uses_alt_scheme": false,
                  "blocks": [
                    { "block_type": "text", "id": "t3", "type": "BODY",
                      "content_alignment": "center", "content": "Ship the layer, not the editor." }
                  ]
                }
                """.trimIndent(),
            )
            .entry("images/diagram.png", ByteArray(4096) { (it % 251).toByte() })
    }
}
