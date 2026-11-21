package com.theoriacodex.app.viewer

import com.theoriacodex.app.recommend.PostTagSignal
import com.theoriacodex.app.ui.navigation.TrailRequest
import com.theoriacodex.app.viewer.ViewerOwnerTestData.post
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ViewerTrailChipsTest {
    private val creator = CreatorProfile(SourceKey.PIXIV, "Mika")
    private fun signals(vararg tags: String) = tags.map { PostTagSignal(it, likedCount = 1) }
    private fun query(vararg tags: String) = Query(QueryMode.Source(SourceKey.PIXIV), tags.toList(), emptyList(), SortMode.NEWEST, null, null)

    @Test
    fun `offers the creator, then Similar, then the top tags the level does not already search`() {
        val chips = viewerTrailChips(creator, similarSupported = true, signals("Cat", "dog", "fox"), query("cat"))

        assertEquals(
            listOf(ViewerTrailChip.Creator(creator), ViewerTrailChip.Similar, ViewerTrailChip.Tag("dog"), ViewerTrailChip.Tag("fox")),
            chips,
        )
    }

    @Test
    fun `leaves out what the post cannot fork and caps the tags`() {
        val chips = viewerTrailChips(null, similarSupported = false, signals("a", "b", "a", "c"), levelQuery = null, maxTags = 2)

        assertEquals(listOf(ViewerTrailChip.Tag("a"), ViewerTrailChip.Tag("b")), chips)
    }

    @Test
    fun `a tag chip searches its tag where the level searched, from the post's source`() {
        val from = post("p", source = SourceKey.DANBOORU)
        val level = query("cat")

        assertEquals(TrailRequest.Tag("long_hair", SourceKey.DANBOORU, level, from), ViewerTrailChip.Tag("long_hair").request(from, level))
        assertEquals("long hair", ViewerTrailChip.Tag("long_hair").label)
    }

    @Test
    fun `a creator's chip names the trail its level was opened as, from any of the creator's posts`() {
        val chip = ViewerTrailChip.Creator(creator).request(post("p"), null)

        assertEquals(TrailRequest.Creator(creator, post("q")).trailKey(), chip.trailKey())
        assertNotEquals(TrailRequest.Creator(creator.copy(displayName = "Other"), post("p")).trailKey(), chip.trailKey())
    }
}
