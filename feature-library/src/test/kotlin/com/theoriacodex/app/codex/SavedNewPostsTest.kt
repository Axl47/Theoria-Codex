package com.theoriacodex.app.codex

import com.theoriacodex.data.repository.FollowedCreator
import com.theoriacodex.data.repository.NewPostMarks
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedNewPostsTest {
    private fun follow(seen: List<String>, latest: List<String>) =
        FollowedCreator(CreatorProfile(SourceKey.PIXIV, "Artist", "1", uploadsQuery = "user:1"), "m", seen, latest, 1L)

    @Test fun `nothing new lists nothing, so no dot shows`() {
        assertTrue(savedNewPosts(listOf(follow(listOf("a"), listOf("a"))), listOf(NewPostMarks())).isEmpty())
    }

    @Test fun `new posts sum per collection and raise it above Likes`() {
        val counts = savedNewPosts(
            listOf(follow(listOf("a"), listOf("b", "a")), follow(emptyList(), listOf("c", "d"))),
            listOf(NewPostMarks(listOf("x"), listOf("y", "x"))),
        )
        assertEquals(mapOf(FOLLOWED_CODEX_ID to 3, SAVED_SEARCHES_CODEX_ID to 1), counts.mapValues { it.value.size })
        val children = savedCodexChildren("likes")
        assertEquals(listOf("Followed", "Likes", "Searches"), children.newFirst(counts - SAVED_SEARCHES_CODEX_ID).map { it.name })
        assertEquals(listOf("Likes", "Followed", "Searches"), children.newFirst(emptyMap()).map { it.name })
    }
}
