package com.theoriacodex.app.triage

import com.theoriacodex.app.codex.ChosenCodex
import com.theoriacodex.app.codex.SaveSuggestion
import com.theoriacodex.app.search.SearchVisibilityFilters
import com.theoriacodex.app.search.filterSearchResults
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.ui.navigation.TriageSeed
import com.theoriacodex.domain.model.DateRange
import com.theoriacodex.domain.model.Post
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TriageDeckPresentationTest {
    private val skies = SaveSuggestion("codex-1", "Skies", emptyList())
    private val post = testPost()

    @Test fun `each direction asks for its action`() {
        assertEquals(TriageChoice.Like, choiceFor(SwipeDirection.Right, skies, "cat"))
        assertEquals(TriageChoice.Skip, choiceFor(SwipeDirection.Left, skies, "cat"))
        assertEquals(TriageChoice.Save(ChosenCodex.Existing("codex-1", "Skies")), choiceFor(SwipeDirection.Up, skies, "cat"))
        assertEquals(TriageChoice.Hide("cat"), choiceFor(SwipeDirection.Down, skies, "cat"))
    }

    @Test fun `up with no Codex to save to asks for nothing so the picker opens`() {
        assertNull(choiceFor(SwipeDirection.Up, null, "cat"))
    }

    @Test fun `down with nothing to hide is a skip`() {
        assertEquals(TriageChoice.Skip, choiceFor(SwipeDirection.Down, skies, null))
    }

    @Test fun `a seed settled to a wider date range keeps the posts the old range dropped`() {
        val old = testPost(sourcePostId = "old", createdAtEpochMs = 10L)
        val recent = testPost(sourcePostId = "recent", createdAtEpochMs = 100L)
        val lastWeek = DateRange(fromEpochMs = 50L, toEpochMs = null)
        val seed = TriageSeed(listOf(recent), SearchVisibilityFilters(dateRange = lastWeek), lengthFilterActive = false) { posts, range, _ ->
            filterSearchResults(posts, SearchVisibilityFilters(dateRange = range), emptySet(), emptySet())
        }
        assertEquals(listOf(recent), seed.filter(listOf(old, recent), emptyMap()))

        val cleared = seed.settled(sameQuery = false, dateRange = null)
        assertEquals(listOf(old, recent), cleared.filter(listOf(old, recent), emptyMap()))
        assertNull(cleared.filters.dateRange)
        assertEquals(emptyList<Post>(), cleared.posts)
        assertEquals(listOf(recent), seed.settled(sameQuery = true, dateRange = lastWeek).posts)
    }

    @Test fun `progress counts the card on screen over the posts so far and marks a feed with more`() {
        val posts = List(60) { testPost(sourcePostId = "p$it") }
        assertEquals("14 / 60", progressLabel(TriageDeckState(posts = posts, index = 13)))
        assertEquals("60 / 60+", progressLabel(TriageDeckState(posts = posts, index = 60, canLoadMore = true)))
    }

    @Test fun `likes, saves, hides and keeps are confirmed in place with display tags and a skip is not`() {
        assertEquals("Liked", noticeLabel(TriageNotice(TriageDecision.Liked(post), failed = false)))
        assertEquals("Saved to Skies", noticeLabel(TriageNotice(TriageDecision.Saved(post, "Skies"), failed = false)))
        assertEquals("Hid wolf girl", noticeLabel(TriageNotice(TriageDecision.Hid(post, "wolf_girl"), failed = false)))
        assertNull(noticeLabel(TriageNotice(TriageDecision.Skipped(post), failed = false)))
        assertEquals("Kept", noticeLabel(TriageNotice(TriageDecision.Kept(post), failed = false)))
    }

    @Test fun `a failed decision says so in the same place`() {
        assertEquals("Couldn't like", noticeLabel(TriageNotice(TriageDecision.Liked(post), failed = true)))
        assertEquals("Couldn't save", noticeLabel(TriageNotice(TriageDecision.Saved(post, "Skies"), failed = true)))
        assertEquals("Couldn't hide", noticeLabel(TriageNotice(TriageDecision.Hid(post, "cat"), failed = true)))
    }
}
