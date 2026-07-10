package com.theoriacodex.sources.hitomi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HitomiProtocolTest {
    @Test
    fun `encodes autocomplete characters using Hitomi path rules`() {
        assertEquals(
            "https://tagindex.hitomi.la/global/t/a/g.json",
            HitomiProtocol.autocompleteUrl(scope = "global", term = "tag"),
        )
        assertEquals(
            "https://tagindex.hitomi.la/artist/k/i/o.json",
            HitomiProtocol.autocompleteUrl(scope = "artist", term = "kio"),
        )
        assertEquals(
            "https://tagindex.hitomi.la/tag/%E6%97%A5/_/slash/dot.json",
            HitomiProtocol.autocompleteUrl(scope = "tag", term = "日 /."),
        )
    }

    @Test
    fun `parses captured global suggestions with namespaces and counts`() {
        val suggestions = HitomiProtocol.parseAutocomplete(fixture("global-tags.json"))

        assertEquals(10, suggestions.size)
        assertEquals(
            HitomiAutocompleteEntry(name = "frottage", count = 3013, namespace = "male"),
            suggestions.first(),
        )
        assertTrue(suggestions.any { it.name == "tagame gengoroh" && it.namespace == "artist" })
        assertTrue(suggestions.any { it.name == "fushigiboshi no futagohime" && it.namespace == "series" })
    }

    @Test
    fun `parses captured scoped artist suggestions`() {
        val suggestions = HitomiProtocol.parseAutocomplete(fixture("artist-kio.json"))

        assertEquals(10, suggestions.size)
        assertEquals("artist", suggestions.first().namespace)
        assertTrue(suggestions.all { it.namespace == "artist" })
        assertTrue(suggestions.any { it.name == "kio seiji" && it.count == 112 })
    }

    @Test
    fun `rejects malformed or unknown autocomplete rows`() {
        assertProtocolFailure { HitomiProtocol.parseAutocomplete("[[\"tag\"]]") }
        assertProtocolFailure { HitomiProtocol.parseAutocomplete("[[\"tag\",1,\"unknown\"]]") }
        assertProtocolFailure { HitomiProtocol.parseAutocomplete("not-json") }
    }

    @Test
    fun `decodes captured animated gallery assignment`() {
        val gallery = HitomiProtocol.parseGalleryAssignment(fixture("gallery-4042375.js"))

        assertEquals("4042375", gallery.get("id").asString)
        assertEquals("artistcg", gallery.get("type").asString)
        assertEquals(44, gallery.getAsJsonArray("files").size())
        assertEquals("najar", gallery.getAsJsonArray("artists")[0].asJsonObject.get("artist").asString)
        assertTrue(
            gallery.getAsJsonArray("tags")
                .any { it.asJsonObject.get("tag").asString == "animated" },
        )
    }

    @Test
    fun `decodes captured anime assignment without treating posters as video pages`() {
        val gallery = HitomiProtocol.parseGalleryAssignment(fixture("gallery-7231.js"))

        assertEquals("7231", gallery.get("id").asString)
        assertEquals("anime", gallery.get("type").asString)
        assertEquals(2, gallery.getAsJsonArray("files").size())
        assertEquals(
            "cheat-item-kanrikyoku-no-oshigoto-ex-1.mp4",
            gallery.get("videofilename").asString,
        )
        assertTrue(gallery.get("artists").isJsonNull)
    }

    @Test
    fun `rejects oversized truncated or unrelated gallery scripts`() {
        assertProtocolFailure {
            HitomiProtocol.parseGalleryAssignment(
                script = fixture("gallery-7231.js"),
                maxResponseChars = 8,
            )
        }
        assertProtocolFailure { HitomiProtocol.parseGalleryAssignment("var galleryinfo = {broken};") }
        assertProtocolFailure { HitomiProtocol.parseGalleryAssignment("window.other = {};") }
    }

    @Test
    fun `captures mutable CDN configuration shape without media payloads`() {
        val script = fixture("gg-shape.js")

        assertTrue(script.contains("switch (g)"))
        assertTrue(script.contains("parseInt(m[2]+m[1], 16)"))
        assertTrue(script.contains("b: '1783681201/'"))
    }

    private fun fixture(name: String): String = requireNotNull(
        javaClass.getResource("/hitomi/2026-07-10/$name"),
    ) { "Missing Hitomi fixture: $name" }.readText()

    private fun assertProtocolFailure(block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        assertTrue("Expected HitomiProtocolException, got $failure", failure is HitomiProtocolException)
    }
}
