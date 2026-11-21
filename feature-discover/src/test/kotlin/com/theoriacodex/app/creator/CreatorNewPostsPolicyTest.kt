package com.theoriacodex.app.creator

import com.theoriacodex.app.testing.testPost
import com.theoriacodex.data.repository.FollowedCreator
import com.theoriacodex.data.repository.RemovedFollow
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.GENERAL_TAG_NAMESPACE
import com.theoriacodex.domain.model.PostTaxonomyTerm
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CreatorNewPostsPolicyTest {
    private val creator = CreatorProfile(SourceKey.PIXIV, "Artist", "1", uploadsQuery = "user:1")
    private fun posts(vararg ids: String) = ids.map { testPost(sourcePostId = it) }

    @Test fun `the divider goes before the first seen post when new posts precede it`() {
        assertEquals("b", newSinceVisitBoundary(posts("n1", "n2", "b", "a"), creator, setOf("a", "b"))?.sourcePostId)
    }

    @Test fun `no divider when nothing loaded was seen, the first post was, or seen ids are another source's`() {
        assertNull(newSinceVisitBoundary(posts("n1", "n2"), creator, setOf("a")))
        assertNull(newSinceVisitBoundary(posts("a", "n1", "b"), creator, setOf("a", "b")))
        val otherSource = listOf(testPost(sourcePostId = "n1"), testPost(SourceKey.IWARA, "a"))
        assertNull(newSinceVisitBoundary(otherSource, creator, setOf("a")))
    }

    @Test fun `sheet rows put new posts first and hold an unfollowed creator's place until restored`() {
        fun follow(id: String, new: Int = 0) = FollowedCreator(
            creator.copy(profileId = id, uploadsQuery = "user:$id"), "m$id",
            latestPostIds = List(new) { "p$it" },
        )
        val quiet = follow("1")
        val busy = follow("2", new = 2)
        val gone = follow("3")
        val rows = followedCreatorRows(listOf(quiet, busy), RemovedFollow(gone, 1))
        assertEquals(listOf("m2", "m1", "vacated:m3"), rows.map { it.key })
        assertEquals(listOf("m1", "m3"), followedCreatorRows(listOf(quiet, gone), RemovedFollow(gone, 1)).map { it.key })
    }

    @Test fun `common tags count posts carrying them, most common first, ignoring one-off tags`() {
        val tagged = listOf(
            testPost(sourcePostId = "1", canonicalTags = listOf("fog", "sea")),
            testPost(sourcePostId = "2", canonicalTags = listOf("sea", "boat")),
            testPost(sourcePostId = "3", canonicalTags = listOf("sea", "fog")),
        )
        val tags = creatorTopTags(creator, tagged)
        assertEquals(listOf("sea" to 3, "fog" to 2), tags.map { it.tag to it.count })
        assertEquals("1", tags.first().from.id.sourcePostId)
    }

    @Test fun `common tags leave out the creator's own tag and tags typed as another kind`() {
        val artist = CreatorProfile(SourceKey.DANBOORU, "ink wash", "ink_wash", uploadsQuery = "ink_wash")
        val typed = listOf(
            PostTaxonomyTerm("sea"),
            PostTaxonomyTerm("highres", sourceNamespace = "meta"),
            PostTaxonomyTerm("ink wash", SearchFacet.ARTIST),
            PostTaxonomyTerm("fog", sourceNamespace = GENERAL_TAG_NAMESPACE),
        )
        val tagged = List(2) { testPost(sourcePostId = "$it", canonicalTags = listOf("ink_wash")).copy(taxonomy = typed) } +
            List(2) { testPost(sourcePostId = "u$it", canonicalTags = listOf("Ink_Wash", "boat")) }
        assertEquals(listOf("sea", "fog", "boat"), creatorTopTags(artist, tagged).map { it.tag })
    }
}
