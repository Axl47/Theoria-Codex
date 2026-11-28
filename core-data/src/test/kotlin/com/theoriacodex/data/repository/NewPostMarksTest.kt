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

    @Test fun `seen posts still in the latest check stay seen however far back they were seen (ADR 0019)`() {
        val first = (0 until 200).map { "old$it" }
        val reshown = NewPostMarks().checked(first, 10L).seen { it in first.takeLast(50).toSet() }
        val fresh = (0 until 50).map { "new$it" }
        val marks = reshown.checked(fresh + first.take(150), 20L)
        assertEquals(fresh, marks.newPostIds)
        val after = marks.seen { it in fresh.toSet() }
        assertEquals(emptyList<String>(), after.newPostIds)
        assertEquals(0, after.checked(fresh + first.take(150), 30L).newPostCount)
    }

    @Test fun `a post seen again after a check left it out is not evicted ahead of older ones (ADR 0019)`() {
        val stable = (0 until 197).map { "s$it" }
        val history = NewPostMarks(listOf("a") + stable + listOf("c", "old"), listOf("a") + stable + listOf("c", "old"), 1L)
        val first = history.checked(listOf("c", "x", "a") + stable, 2L).seen { it == "x" }
        val second = first.checked(listOf("y", "x") + stable, 3L).seen { it == "y" }
        assertEquals(0, second.checked(listOf("a") + stable, 4L).newPostCount)
    }

    @Test fun `checks are due when the newest attempt is three hours old`() {
        val hours = 60 * 60 * 1000L
        assertTrue(newPostsCheckDue(listOf(null), 0L))
        assertFalse(newPostsCheckDue(listOf(0L, 2 * hours, null), 4 * hours))
        assertTrue(newPostsCheckDue(listOf(0L, 2 * hours), 5 * hours))
    }

    @Test fun `a refresh is one write that stamps failures as attempts`() = runTest {
        val repository = CreatorFollowsRepository(InMemorySettingsRepository())
        val creators = listOf("1", "2").map { CreatorProfile(SourceKey.PIXIV, "Artist $it", it, uploadsQuery = "user:$it") }
        creators.forEach { repository.follow(it, listOf(post("old"))) }
        val (checked, failed) = repository.observe().first()
        repository.recordChecks(mapOf(checked.membershipId to listOf(post("new"), post("old")), failed.membershipId to null), 42L)
        val (afterChecked, afterFailed) = repository.observe().first()
        assertEquals(1, afterChecked.newPostCount)
        assertEquals(42L, afterChecked.checkedAtEpochMs)
        assertEquals(null, afterFailed.checkedAtEpochMs)
        assertEquals(42L, afterFailed.attemptedAtEpochMs)
    }

    @Test fun `opening Followed clears only the posts it showed`() = runTest {
        val repository = CreatorFollowsRepository(InMemorySettingsRepository())
        val creators = listOf("1", "2").map { CreatorProfile(SourceKey.PIXIV, "Artist $it", it, uploadsQuery = "user:$it") }
        creators.forEach { repository.follow(it, listOf(post("old"))) }
        repository.recordChecks(repository.observe().first().associate { it.membershipId to listOf(post("new"), post("old")) }, 42L)
        assertEquals(listOf(1, 1), repository.observe().first().map { it.newPostCount })
        repository.markSeen(emptySet())
        assertEquals(listOf(1, 1), repository.observe().first().map { it.newPostCount })
        repository.markSeen(setOf("PIXIV:new"))
        assertEquals(listOf(0, 0), repository.observe().first().map { it.newPostCount })
    }

    @Test fun `seen takes only the shown ids`() {
        val marks = NewPostMarks().checked(listOf("a"), 10L).checked(listOf("c", "b", "a"), 20L)
        assertEquals(listOf("b"), marks.seen { it == "c" }.newPostIds)
    }

    private fun post(id: String) = requireNotNull(PostStorageCodec.decode(PostStorageRecord(source = "PIXIV", sourcePostId = id)))
}
