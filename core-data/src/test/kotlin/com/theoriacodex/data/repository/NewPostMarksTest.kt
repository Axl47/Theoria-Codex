package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.data.storage.PostStorageCodec
import com.theoriacodex.data.storage.PostStorageRecord
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NewPostMarksTest {
    @Test fun `first check with nothing seen sets the baseline without counting`() {
        val marks = NewPostMarks().checked(listOf("a", "b"), 10L)
        assertEquals(0, marks.newPostCount)
        assertEquals(10L, marks.checkedAtEpochMs)
    }

    @Test fun `later checks count unseen ids until they are seen`() {
        val marks = NewPostMarks().checked(listOf("a"), 10L).checked(listOf("c", "b", "a", "c"), 20L)
        assertEquals(listOf("c", "b"), marks.newPostIds)
        val seen = marks.seen()
        assertEquals(0, seen.newPostCount)
        assertEquals(0, seen.checked(listOf("b", "a"), 30L).newPostCount)
        assertEquals(1, seen.checked(listOf("d", "a"), 30L).newPostCount)
    }

    @Test fun `checks are due when the newest attempt is three hours old`() {
        val hours = 60 * 60 * 1000L
        assertTrue(newPostsCheckDue(listOf(null), 0L))
        assertFalse(newPostsCheckDue(listOf(0L, 2 * hours, null), 4 * hours))
        assertTrue(newPostsCheckDue(listOf(0L, 2 * hours), 5 * hours))
    }

    @Test fun `opening Followed clears every creator's new posts`() = runTest {
        val repository = CreatorFollowsRepository(InMemorySettingsRepository())
        fun post(id: String) = requireNotNull(PostStorageCodec.decode(PostStorageRecord(source = "PIXIV", sourcePostId = id)))
        val creators = listOf("1", "2").map { CreatorProfile(SourceKey.PIXIV, "Artist $it", it, uploadsQuery = "user:$it") }
        creators.forEach { repository.follow(it, listOf(post("old"))) }
        repository.observe().first().forEach { repository.recordCheck(it, listOf(post("new"), post("old")), 42L) }
        assertEquals(listOf(1, 1), repository.observe().first().map { it.newPostCount })
        repository.markLatestSeen()
        assertEquals(listOf(0, 0), repository.observe().first().map { it.newPostCount })
    }
}
