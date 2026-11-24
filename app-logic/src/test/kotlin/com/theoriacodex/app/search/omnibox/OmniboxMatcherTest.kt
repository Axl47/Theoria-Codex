package com.theoriacodex.app.search.omnibox

import org.junit.Assert.assertEquals
import org.junit.Test

class OmniboxMatcherTest {
    @Test fun `title prefixes rank above word starts, which rank above matches inside a word`() {
        val entries = listOf(
            action(OmniboxKind.CODEX, "Landscapes"),
            action(OmniboxKind.CODEX, "Island trip"),
            action(OmniboxKind.CODEX, "Night scapes"),
            action(OmniboxKind.CODEX, "Highlands"),
        )
        assertEquals(listOf("Landscapes", "Island trip", "Highlands"), titles(matchOmnibox(entries, "LAND")))
        assertEquals(listOf("Night scapes", "Landscapes"), titles(matchOmnibox(entries, " scapes ")))
    }

    @Test fun `keywords find an entry and one letter only matches the start of a word (ADR 0083)`() {
        val entries = listOf(
            OmniboxEntry.Toggle("Instant Seek", checked = false, keywords = listOf("scrub"), set = {}),
            action(OmniboxKind.COMMAND, "Storage & caching", keywords = listOf("cache")),
        )
        assertEquals(listOf("Instant Seek"), titles(matchOmnibox(entries, "scr")))
        assertEquals(listOf("Storage & caching"), titles(matchOmnibox(entries, "c")))
        assertEquals(listOf("Storage & caching"), titles(matchOmnibox(entries, "ac")))
        assertEquals(emptyList<OmniboxEntry>(), matchOmnibox(entries, "t"))
        assertEquals(emptyList<OmniboxEntry>(), matchOmnibox(entries, "  "))
    }

    @Test fun `when anything else matches, only the top two tags come before it (ADR 0083)`() {
        val tags = (1..5).map { "tag $it" }
        val codex = matchOmnibox(listOf(action(OmniboxKind.CODEX, "Tagged")), "tag")

        val placed = placeOmniboxMatches(tags, codex)
        assertEquals(listOf("tag 1", "tag 2"), placed.leading)
        assertEquals(listOf("Tagged"), titles(placed.matches))
        assertEquals(listOf("tag 3", "tag 4", "tag 5"), placed.trailing)
        assertEquals(TagsAroundOmnibox(tags, emptyList(), emptyList()), placeOmniboxMatches(tags, emptyList()))
    }

    @Test fun `each kind keeps its best few and equal matches follow the kinds' order (ADR 0083)`() {
        val entries = listOf(
            action(OmniboxKind.COMMAND, "Blue mode"),
            action(OmniboxKind.CREATOR, "Bluebird"),
        ) + (1..5).map { action(OmniboxKind.RECENT_SEARCH, "blue $it") } +
            action(OmniboxKind.CODEX, "Deep blue") +
            action(OmniboxKind.CODEX, "Blues")
        assertEquals(
            listOf("Blues", "Bluebird", "blue 1", "blue 2", "Blue mode", "Deep blue"),
            titles(matchOmnibox(entries, "blue", capPerKind = 2)),
        )
        val crowded = ('a'..'d').map { action(OmniboxKind.CODEX, "blue $it") } +
            (1..5).map { action(OmniboxKind.RECENT_SEARCH, "blue $it") }
        assertEquals(
            listOf("blue a", "blue b", "blue c", "blue 1", "blue 2", "blue 3"),
            titles(matchOmnibox(crowded, "blue")),
        )
    }

    private fun action(kind: OmniboxKind, title: String, keywords: List<String> = emptyList()) =
        OmniboxEntry.Action(kind, title, keywords = keywords, run = {})

    private fun titles(entries: List<OmniboxEntry>) = entries.map(OmniboxEntry::title)
}
