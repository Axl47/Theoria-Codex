package com.theoriacodex.app.ui.routes

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.theoriacodex.app.media.AnimatedDurationRange
import com.theoriacodex.app.search.SearchVisibilityFilters
import com.theoriacodex.app.viewer.ViewerSession
import com.theoriacodex.app.viewer.ViewerViewModel
import com.theoriacodex.app.viewer.state.ViewerEffect
import com.theoriacodex.app.viewer.state.ViewerSessionIdentity
import com.theoriacodex.app.viewer.state.ViewerUiState
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerRoutePolicyTest {
    @Test
    fun `live source requires exact query identity and reapplies search visibility`() {
        val liked = post("liked")
        val saved = post("saved")
        val visible = post("visible")
        val session = session(
            queryHash = "search:active",
            source = ViewerStreamSource.SEARCH,
            filters = SearchVisibilityFilters(
                hideLiked = true,
                hideSaved = true,
                animatedDurationRange = AnimatedDurationRange.Full,
            ),
        )
        val state = ViewerRouteLiveSourceState(
            search = ViewerRouteLiveSourceSnapshot(
                queryHash = "search:active",
                results = listOf(liked, saved, visible),
            ),
            likedPostIds = setOf(liked.id),
            savedPostIds = setOf(saved.id),
        )

        assertEquals(listOf(visible), state.visiblePostsFor(session))
        assertNull(
            state.copy(
                search = state.search.copy(queryHash = "search:replacement"),
            ).visiblePostsFor(session),
        )
    }

    @Test
    fun `for you keeps its launch policy while still requiring the active seed`() {
        val liked = post("liked")
        val session = session(
            queryHash = "for_you:seed",
            source = ViewerStreamSource.FOR_YOU,
            filters = SearchVisibilityFilters(hideLiked = true),
        )
        val state = ViewerRouteLiveSourceState(
            forYou = ViewerRouteLiveSourceSnapshot(
                queryHash = "for_you:seed",
                results = listOf(liked),
                canLoadMore = true,
            ),
            likedPostIds = setOf(liked.id),
        )

        assertEquals(listOf(liked), state.visiblePostsFor(session))
        assertTrue(state.forSource(ViewerStreamSource.FOR_YOU)?.canLoadMore == true)
    }

    @Test
    fun `buffered effect is current only for its originating viewer session`() {
        val post = post("post")
        val oldSession = ViewerSessionIdentity("old")
        val effect = ViewerEffect.SavePost(oldSession, post.id)

        assertTrue(effect.isCurrentFor(ViewerUiState(session = oldSession)))
        assertFalse(effect.isCurrentFor(ViewerUiState(session = ViewerSessionIdentity("new"))))
        assertFalse(effect.isCurrentFor(ViewerUiState.Empty))
    }

    @Test
    fun `owner handle exposes only live session coordination and rejects a cleared owner`() {
        val owner = ViewerViewModel(SavedStateHandle())
        val store = ViewModelStore()
        val provider = ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = owner as T
            },
        )
        provider[ViewerViewModel::class.java]
        val handle = ViewerRouteOwnerHandle(owner)
        owner.replaceSession(session("search:owned", ViewerStreamSource.SEARCH))

        assertEquals("search:owned", handle.session?.value?.context?.queryHash)
        assertTrue(handle.clearSession())
        assertNull(handle.session?.value)

        store.clear()

        assertNull(handle.session)
        assertFalse(handle.clearSession())
    }

    private fun session(
        queryHash: String,
        source: ViewerStreamSource,
        filters: SearchVisibilityFilters = SearchVisibilityFilters(),
    ): ViewerSession = ViewerSession(
        posts = listOf(post("initial")),
        context = ViewerLaunchContext(
            queryHash = queryHash,
            startIndex = 0,
            streamSource = source,
            scrollOffsetHint = 0,
        ),
        liveSearchBinding = true,
        searchVisibilityFilters = filters,
        sessionId = "session-$queryHash",
    )

    private fun post(id: String): Post = Post(
        id = PostId(SourceKey.PIXIV, id),
        preview = ImageRef(
            url = "https://example.test/$id.jpg",
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
