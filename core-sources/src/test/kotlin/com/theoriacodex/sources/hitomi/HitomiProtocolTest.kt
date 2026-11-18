package com.theoriacodex.sources.hitomi

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        assertProtocolFailure { HitomiProtocol.parseAutocomplete("[[\"tag\",null,\"tag\"]]") }
        assertProtocolFailure { HitomiProtocol.parseAutocomplete("[[null,1,\"tag\"]]") }
    }

    @Test
    fun `reads autocomplete counts sent as strings and names sent as numbers`() {
        assertEquals(
            listOf(
                HitomiAutocompleteEntry(name = "kio", count = 12, namespace = "artist"),
                HitomiAutocompleteEntry(name = "7", count = 3, namespace = "tag"),
            ),
            HitomiProtocol.parseAutocomplete("[[\" kio \",\"12\",\"Artist\"],[7,3.0,\"tag\",\"extra\"]]"),
        )
    }

    @Test
    fun `decodes captured animated gallery assignment`() {
        val gallery = HitomiProtocol.parseGalleryAssignment(fixture("gallery-4042375.js"))

        assertEquals("4042375", gallery.getValue("id").jsonPrimitive.content)
        assertEquals("artistcg", gallery.getValue("type").jsonPrimitive.content)
        assertEquals(44, gallery.getValue("files").jsonArray.size)
        assertEquals("najar", gallery.getValue("artists").jsonArray[0].jsonObject.getValue("artist").jsonPrimitive.content)
        assertTrue(
            gallery.getValue("tags").jsonArray
                .any { it.jsonObject.getValue("tag").jsonPrimitive.content == "animated" },
        )
    }

    @Test
    fun `decodes captured anime assignment without treating posters as video pages`() {
        val gallery = HitomiProtocol.parseGalleryAssignment(fixture("gallery-7231.js"))

        assertEquals("7231", gallery.getValue("id").jsonPrimitive.content)
        assertEquals("anime", gallery.getValue("type").jsonPrimitive.content)
        assertEquals(2, gallery.getValue("files").jsonArray.size)
        assertEquals(
            "cheat-item-kanrikyoku-no-oshigoto-ex-1.mp4",
            gallery.getValue("videofilename").jsonPrimitive.content,
        )
        assertEquals(JsonNull, gallery.getValue("artists"))
    }

    @Test
    fun `parses supported gallery declarations without JVM specific regex behavior`() {
        listOf("var", "let", "const").forEach { keyword ->
            val gallery = HitomiProtocol.parseGalleryAssignment(
                "$keyword\n galleryinfo\t=\n{\"id\":4042375};",
            )
            assertEquals(4_042_375, gallery.getValue("id").jsonPrimitive.int)
        }
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
        assertProtocolFailure { HitomiProtocol.parseGalleryAssignment("var other galleryinfo = {};") }
        assertProtocolFailure { HitomiProtocol.parseGalleryAssignment("var galleryinfo = {}; trailing") }
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
