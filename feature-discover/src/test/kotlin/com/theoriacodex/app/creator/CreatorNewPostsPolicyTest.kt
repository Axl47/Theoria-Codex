package com.theoriacodex.app.creator

import com.theoriacodex.app.testing.testPost
import com.theoriacodex.data.repository.FollowedCreator
import com.theoriacodex.data.repository.RemovedFollow
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CreatorNewPostsPolicyTest {
    private val creator = CreatorProfile(SourceKey.PIXIV, "Artist", "1", uploadsQuery = "user:1")
    private fun posts(vararg ids: String) = ids.map { testPost(sourcePostId = it) }

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

}
