package com.theoriacodex.app.triage

import com.theoriacodex.app.codex.SaveSuggestion
import com.theoriacodex.app.testing.testPost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TriageDeckPresentationTest {
    private val skies = SaveSuggestion("codex-1", "Skies", emptyList())
    private val post = testPost()

    @Test fun `each direction asks for its action`() {
        assertEquals(TriageChoice.Like, choiceFor(SwipeDirection.Right, skies, "cat"))
        assertEquals(TriageChoice.Skip, choiceFor(SwipeDirection.Left, skies, "cat"))
        assertEquals(TriageChoice.Save("codex-1", "Skies"), choiceFor(SwipeDirection.Up, skies, "cat"))
        assertEquals(TriageChoice.Hide("cat"), choiceFor(SwipeDirection.Down, skies, "cat"))
    }

    @Test fun `up with no Codex to save to asks for nothing so the picker opens`() {
        assertNull(choiceFor(SwipeDirection.Up, null, "cat"))
    }

    @Test fun `down with nothing to hide is a skip`() {
        assertEquals(TriageChoice.Skip, choiceFor(SwipeDirection.Down, skies, null))
    }

    @Test fun `progress counts the card on screen over the posts so far and marks a feed with more`() {
        val posts = List(60) { testPost(sourcePostId = "p$it") }
        assertEquals("14 / 60", progressLabel(TriageDeckState(posts = posts, index = 13)))
        assertEquals("60 / 60+", progressLabel(TriageDeckState(posts = posts, index = 60, canLoadMore = true)))
    }

    @Test fun `likes, saves and hides are confirmed in place and a skip is not`() {
        assertEquals("Liked", confirmationLabel(TriageDecision.Liked(post)))
        assertEquals("Saved to Skies", confirmationLabel(TriageDecision.Saved(post, "Skies")))
        assertEquals("Hid cat", confirmationLabel(TriageDecision.Hid(post, "cat")))
        assertNull(confirmationLabel(TriageDecision.Skipped(post)))
    }
}
