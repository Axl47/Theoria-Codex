package com.theoriacodex.app.ui.navigation

import com.theoriacodex.app.recommend.TagAffinity
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Test

class TrailForkTest {
    private val creator = CreatorProfile(SourceKey.PIXIV, "Mika")
    private fun signals(vararg tags: String) = tags.map { TagAffinity(it, interest = 1.0, score = 1.0, universal = false) }

    @Test
    fun `offers the creator, then Similar, then the top tags not already covered`() {
        val forks = trailForks(creator, similarSupported = true, signals("Cat", "dog", "fox"), covered = listOf("cat"))

        assertEquals(listOf(TrailFork.Creator(creator), TrailFork.Similar, TrailFork.Tag("dog"), TrailFork.Tag("fox")), forks)
    }

    @Test
    fun `leaves out what the post cannot fork and caps the tags`() {
        val forks = trailForks(null, similarSupported = false, signals("a", "b", "a", "c", "d", "e", "f"), covered = emptyList())

        assertEquals(listOf("a", "b", "c", "d", "e").map(TrailFork::Tag), forks)
    }

    @Test
    fun `underscored and spaced spellings of a tag count as one`() {
        val forks = trailForks(null, similarSupported = false, signals("big_breasts", "Long_Hair", "long hair"), covered = listOf("big breasts"))

        assertEquals(listOf(TrailFork.Tag("Long_Hair")), forks)
    }

    @Test
    fun `leaves out tags that say nothing about the post`() {
        val universal = TagAffinity("landscape", interest = 1.0, score = 1.0, universal = true)
        val forks = trailForks(null, similarSupported = false, listOf(universal) + signals("dog"), covered = emptyList())

        assertEquals(listOf(TrailFork.Tag("dog")), forks)
    }

    @Test
    fun `a tag fork searches its tag where the level searched, from the post's source`() {
        val from = testPost(source = SourceKey.DANBOORU, sourcePostId = "p")
        val level = Query(QueryMode.Source(SourceKey.PIXIV), listOf("cat"), emptyList(), SortMode.NEWEST, null, null)

        assertEquals(TrailRequest.Tag("long_hair", SourceKey.DANBOORU, level, from), TrailFork.Tag("long_hair").request(from, level))
        assertEquals("long hair", TrailFork.Tag("long_hair").label)
    }
}
