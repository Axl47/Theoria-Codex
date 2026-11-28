package com.theoriacodex.app.ui

import com.theoriacodex.app.codex.FOLLOWED_CODEX_ID
import com.theoriacodex.app.codex.SAVED_SEARCHES_CODEX_ID
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.ViewerStreamSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NewPostsCollectionTest {
    private fun codex(id: String) = ViewerLaunchContext("codex:$id", 0, ViewerStreamSource.CODEX, 0)

    @Test
    fun `only a Viewer opened from Followed or Searches hides the posts it shows from an open collection's markers (ADR 0019)`() {
        assertEquals(FOLLOWED_CODEX_ID, newPostsCollection(codex(FOLLOWED_CODEX_ID)))
        assertEquals(SAVED_SEARCHES_CODEX_ID, newPostsCollection(codex(SAVED_SEARCHES_CODEX_ID)))
        assertNull(newPostsCollection(codex("my-codex")))
        assertNull(newPostsCollection(codex(FOLLOWED_CODEX_ID).copy(streamSource = ViewerStreamSource.RECENTS)))
        assertNull(newPostsCollection(null))
    }
}
