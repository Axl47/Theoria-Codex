package com.theoriacodex.sources.hitomi

import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.model.PostTaxonomyTerm
import com.theoriacodex.domain.model.SearchFacet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HitomiGalleryMetadataTest {
    @Test
    fun `reads numbers sent as strings and ignores fields of the wrong type (ADR 0043, 0054)`() {
        val gallery = parse(
            """
            {
              "id": 314,
              "files": [
                {"hash": "$HASH", "name": "1.webp", "hasavif": "1", "width": "800", "height": null},
                {"hash": "$HASH", "name": "2.webp", "hasavif": 1, "width": 1600.0, "height": "tall"},
                {"hash": "$HASH", "name": "3.webp", "hasavif": 0, "width": 0, "height": -5},
                {"hash": "$HASH", "name": "4.webp", "hasavif": ""}
              ],
              "type": "  Manga ",
              "title": " ",
              "japanese_title": "Fallback title",
              "date": "not a date",
              "datepublished": "2024-01-02"
            }
            """,
        )

        assertEquals(314, gallery.id)
        assertEquals(
            listOf(
                ParsedHitomiFile(HASH, "1.webp", hasAvif = true, width = 800, height = null),
                ParsedHitomiFile(HASH, "2.webp", hasAvif = true, width = 1600, height = null),
                ParsedHitomiFile(HASH, "3.webp", hasAvif = false, width = null, height = null),
                ParsedHitomiFile(HASH, "4.webp", hasAvif = false, width = null, height = null),
            ),
            gallery.files,
        )
        assertEquals("manga", gallery.type)
        assertEquals("Fallback title", gallery.title)
        assertEquals(1_704_153_600_000L, gallery.createdAtEpochMs)
    }

    @Test
    fun `falls back to the requested id and a generated title`() {
        val gallery = parse("""{"id": "abc", "files": [{"hash": "$HASH", "name": "1.jpg"}]}""")

        assertEquals(99, gallery.id)
        assertEquals("Hitomi #99", gallery.title)
        assertNull(gallery.createdAtEpochMs)
    }

    @Test
    fun `keeps the last value of a repeated key`() {
        val gallery = parse("""{"id": "1", "files": [{"hash": "$HASH", "name": "1.jpg"}], "id": "2"}""")

        assertEquals(2, gallery.id)
    }

    @Test
    fun `reads tag namespace flags in every shape Hitomi sends (ADR 0043, 0054)`() {
        val gallery = parse(
            """
            {
              "files": [{"hash": "$HASH", "name": "1.jpg"}],
              "tags": [
                {"tag": "string flag", "female": "1", "male": ""},
                {"tag": "number flag", "female": 0, "male": 1},
                {"tag": "boolean flag", "female": true},
                {"tag": "blank flags", "female": "", "male": null},
                {"tag": 42},
                {"female": "1"},
                "not an object",
                null
              ],
              "artists": null,
              "characters": {"character": "not a list"},
              "groups": [{"group": "circle"}, {"group": null}, {"group": {"name": "nested"}}],
              "language": "english"
            }
            """,
        )

        assertEquals(
            listOf(
                PostTaxonomyTerm("string flag", SearchFacet.TAG, HITOMI_FEMALE_NAMESPACE),
                PostTaxonomyTerm("number flag", SearchFacet.TAG, HITOMI_MALE_NAMESPACE),
                PostTaxonomyTerm("boolean flag", SearchFacet.TAG, HITOMI_FEMALE_NAMESPACE),
                PostTaxonomyTerm("blank flags", SearchFacet.TAG, HITOMI_TAG_NAMESPACE),
                PostTaxonomyTerm("42", SearchFacet.TAG, HITOMI_TAG_NAMESPACE),
                PostTaxonomyTerm("circle", SearchFacet.GROUP, HITOMI_GROUP_NAMESPACE),
                PostTaxonomyTerm("english", SearchFacet.LANGUAGE, HITOMI_LANGUAGE_NAMESPACE),
            ),
            gallery.taxonomy,
        )
        assertTrue(gallery.creators.isEmpty())
        assertEquals(emptyList<PostTaxonomyTerm>(), parse("""{"files": [{"hash": "$HASH", "name": "1.jpg"}], "tags": null}""").taxonomy)
    }

    @Test
    fun `skips artists without a usable name`() {
        val gallery = parse(
            """
            {
              "files": [{"hash": "$HASH", "name": "1.jpg"}],
              "artists": [{"artist": "  Najar "}, {"artist": null}, {"url": "/artist/x-all.html"}, "x"]
            }
            """,
        )

        assertEquals(listOf("Najar"), gallery.creators.map { creator -> creator.displayName })
    }

    @Test
    fun `plays an anime video without image files`() {
        val gallery = parse("""{"type": "anime", "videofilename": "clip.mp4", "files": null}""")

        assertTrue(gallery.files.isEmpty())
        assertTrue(gallery.hasPlayableVideo)
        assertFalse(gallery.animated)
    }

    @Test
    fun `rejects galleries whose files cannot be used`() {
        listOf(
            """{"files": ["not an object"]}""",
            """{"files": [{"name": "1.jpg"}]}""",
            """{"files": [{"hash": "not-hex", "name": "1.jpg"}]}""",
            """{"files": []}""",
        ).forEach { body ->
            val failure = runCatching { parse(body) }.exceptionOrNull()
            assertTrue("Expected a parse failure for $body, got $failure", failure is HitomiGalleryException)
            assertEquals(SourceFailureReason.PARSE, (failure as HitomiGalleryException).reason)
        }
    }

    private fun parse(body: String): ParsedHitomiGallery =
        parseHitomiGallery(HitomiProtocol.parseGalleryAssignment("var galleryinfo = ${body.trim()}"), 99)

    private companion object {
        val HASH = "a".repeat(64)
    }
}
