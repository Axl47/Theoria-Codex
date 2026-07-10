package com.theoriacodex.app.appshell

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.theoriacodex.app.search.SearchVisibilityFilters
import com.theoriacodex.app.viewer.ViewerSession
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
