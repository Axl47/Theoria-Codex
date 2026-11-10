package com.theoriacodex.app.statistics

import com.theoriacodex.data.repository.CodexPostStatistics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatsShareTest {
    private val posts = (1..7).associate { "GELBOORU:$it" to CodexPostStatistics("Post $it", opens = 10L - it, videoMs = 100L * it) }
    private val landscapes = summary("a", "Landscapes", posts)
    private val portraits = summary("b", "Portraits", mapOf("PIXIV:1" to CodexPostStatistics("Shared", opens = 3L, imageMs = 50L)))

    @Test
    fun `leaving out a post removes its activity and the next post takes its place`() {
        val codex = shareCodex(landscapes, setOf("GELBOORU:1"))

        assertEquals(posts.values.sumOf { it.opens } - 9L, codex.postOpens)
        assertEquals(10_000L - 100L, codex.watchingMs)
        val keys = codex.posts.orEmpty().map { it?.key }
        assertEquals(false, "GELBOORU:1" in keys)
        assertEquals(true, "GELBOORU:6" in keys)
    }

    @Test
    fun `a left out Codex and an empty Codex never reach the file`() {
        val empty = summary("c", "Empty", emptyMap(), watchingMs = 0L, entries = 0L)
        val file = buildStatsShare(listOf(landscapes, portraits, empty), StatsShareSelection(excludedCodexIds = setOf("a")), " Sam ")

        assertEquals("Sam", file.name)
        assertEquals(listOf("Portraits"), file.codices.orEmpty().map { it?.name })
    }

    @Test
    fun `a file survives the round trip and rejects anything else`() {
        val file = buildStatsShare(listOf(portraits))

        assertEquals(file, parseStatsShare(encodeStatsShare(file)))
        assertNull(parseStatsShare("""{"format":"something-else","version":1}"""))
        assertNull(parseStatsShare("""{"format":"theoria-codex-stats","version":99}"""))
        assertNull(parseStatsShare("not json"))
    }

    @Test
    fun `hostile values are bounded and unusable posts dropped`() {
        val parsed = parseStatsShare(
            """{"format":"theoria-codex-stats","version":1,"codices":[null,{"name":""},
            {"name":"Ok","watchingMs":-5,"entries":9000000000000000000,"posts":[
              {"key":"NOT_A_SOURCE:1","label":"x"},{"key":"GELBOORU:2","label":"Fine","opens":-1}]}]}""",
        )
        val codex = parsed?.codices.orEmpty().filterNotNull().single()

        assertEquals(0L, codex.watchingMs)
        assertEquals(1_000_000_000_000_000L, codex.entries)
        assertEquals(listOf("GELBOORU:2"), codex.posts.orEmpty().map { it?.key })
        assertEquals(0L, codex.posts.orEmpty().single()?.opens)
    }

    @Test
    fun `comparison pairs Codices by name and finds posts in common`() {
        val theirs = StatsShareFile(name = "Sam", codices = listOf(
            StatsShareCodex(name = "portraits", watchingMs = 9L, postOpens = 2L, posts = listOf(StatsSharePost("PIXIV:1", "Theirs", opens = 2L))),
            StatsShareCodex(name = "Only theirs", watchingMs = 1L),
        ))

        val comparison = compareStats(buildStatsShare(listOf(portraits)), theirs)

        assertEquals(setOf("Portraits", "Only theirs"), comparison.codices.map { it.name }.toSet())
        assertEquals(9L, comparison.codices.first { it.name == "Portraits" }.theirWatchingMs)
        assertEquals(listOf("PIXIV:1"), comparison.postsInCommon.map { it.key })
        assertEquals(3L to 2L, comparison.postsInCommon.single().let { it.yourOpens to it.theirOpens })
        assertEquals(2L, comparison.theirs.postOpens)
    }

    private fun summary(id: String, name: String, posts: Map<String, CodexPostStatistics>, watchingMs: Long = 10_000L, entries: Long = 1L) =
        CodexStatisticsSummary(id, name, entries, 0L, watchingMs, posts.values.sumOf { it.opens }, emptyList(), emptyList(), emptyList(), posts)
}
