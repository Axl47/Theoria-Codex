package com.theoriacodex.app.appshell

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.SavedStateHandle
import com.theoriacodex.app.search.SearchVisibilityFilters
import com.theoriacodex.app.viewer.ViewerSession
import com.theoriacodex.app.viewer.ViewerViewModel
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerSessionRetentionViewModelTest {
    @Test
    fun `same activity store retains viewer session for recreated shell`() {
        val store = ViewModelStore()
        try {
            val provider = ViewModelProvider(store, ViewModelProvider.NewInstanceFactory())
            val firstShellOwner = provider[ViewerSessionRetentionViewModel::class.java]
            val retained = viewerSession(postId = "first")

            firstShellOwner.retain(retained)

            val recreatedShellOwner = provider[ViewerSessionRetentionViewModel::class.java]
            assertSame(firstShellOwner, recreatedShellOwner)
            assertEquals(retained, recreatedShellOwner.session.value)
        } finally {
            store.clear()
        }
    }

    @Test
    fun `update replaces retained session without changing holder identity`() {
        val holder = ViewerSessionRetentionViewModel()
        holder.retain(viewerSession(postId = "first"))
        val replacementPost = post("resolved")

        holder.update { current -> current.copy(posts = listOf(replacementPost)) }

        assertEquals(listOf(replacementPost), holder.session.value?.posts)
    }

    @Test
    fun `clear removes retained viewer session`() {
        val holder = ViewerSessionRetentionViewModel()
        holder.retain(viewerSession(postId = "first"))

        holder.clear()

        assertNull(holder.session.value)
    }

    @Test
    fun `update is a no-op when no viewer session exists`() {
        val holder = ViewerSessionRetentionViewModel()
        var invoked = false

        holder.update { current ->
            invoked = true
            current
        }

        assertEquals(false, invoked)
        assertNull(holder.session.value)
    }

    @Test
    fun `retained navigation payload transfers once into the route owner`() {
        val holder = ViewerSessionRetentionViewModel()
        val retained = viewerSession(postId = "bridge")
        holder.retain(retained)
        val owner = ViewerViewModel(SavedStateHandle())

        assertTrue(holder.handoffTo(owner, retained.sessionId))

        assertNull(holder.session.value)
        assertEquals(retained, owner.session.value)
        assertEquals(retained.sessionId, owner.state.value.session?.value)
        assertEquals(retained.posts, owner.state.value.pages.map { page -> page.post })
        assertEquals(false, holder.handoffTo(owner, retained.sessionId))
    }

    @Test
    fun `exiting route claim cannot consume a newer rapid reentry payload`() {
        val holder = ViewerSessionRetentionViewModel()
        val exitingSession = viewerSession(postId = "exiting")
        val reentrySession = viewerSession(postId = "reentry")
        val exitingClaim = exitingSession.sessionId
        holder.retain(exitingSession)
        holder.retain(reentrySession)
        val exitingOwner = ViewerViewModel(SavedStateHandle())
        val reentryOwner = ViewerViewModel(SavedStateHandle())

        assertEquals(false, holder.handoffTo(exitingOwner, exitingClaim))
        assertNull(exitingOwner.session.value)
        assertEquals(reentrySession, holder.session.value)

        assertTrue(holder.handoffTo(reentryOwner, reentrySession.sessionId))
        assertEquals(reentrySession, reentryOwner.session.value)
        assertNull(holder.session.value)
    }

    private fun viewerSession(postId: String): ViewerSession {
        return ViewerSession(
            posts = listOf(post(postId)),
            context = ViewerLaunchContext(
                queryHash = "search:$postId",
                startIndex = 0,
                streamSource = ViewerStreamSource.SEARCH,
                scrollOffsetHint = 0,
            ),
            liveSearchBinding = true,
            searchVisibilityFilters = SearchVisibilityFilters(),
        )
    }

    private fun post(postId: String): Post {
        return Post(
            id = PostId(SourceKey.PIXIV, postId),
            preview = ImageRef(
                url = "https://example.test/$postId.jpg",
                localPath = null,
                mime = "image/jpeg",
            ),
            full = null,
            pageUrl = null,
            width = null,
            height = null,
            canonicalTags = emptyList(),
            rawTags = emptyList(),
            authorName = null,
            createdAtEpochMs = null,
        )
    }
}
