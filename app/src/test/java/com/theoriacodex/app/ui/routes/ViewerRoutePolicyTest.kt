package com.theoriacodex.app.ui.routes

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.theoriacodex.app.media.AnimatedDurationRange
import com.theoriacodex.app.search.SearchVisibilityFilters
import com.theoriacodex.app.viewer.ViewerSession
import com.theoriacodex.app.related.ViewerFeedSnapshot
import com.theoriacodex.app.viewer.ViewerViewModel
import com.theoriacodex.app.viewer.state.ViewerEffect
import com.theoriacodex.app.viewer.state.ViewerSessionIdentity
import com.theoriacodex.app.viewer.state.ViewerUiState
import com.theoriacodex.app.viewer.testViewerOwner
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.RecentPostSection
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
    fun `related launches keep explicit origin and reject a replaced source query`() {
        val snapshot = ViewerFeedSnapshot(queryHash = "origin")
        val state = ViewerRouteLiveSourceState(feeds = mapOf(ViewerStreamSource.SEARCH to snapshot))
        val related = session("related:PIXIV:seed", ViewerStreamSource.RELATED, SearchVisibilityFilters())
        val bound = related.copy(relatedBinding = com.theoriacodex.app.viewer.ViewerRelatedBinding(ViewerStreamSource.SEARCH, "origin"))
        assertEquals(snapshot, state.relatedSourceFor(bound))
        assertEquals(null, state.relatedSourceFor(related))
        assertEquals(null, state.copy(feeds = mapOf(ViewerStreamSource.SEARCH to snapshot.copy(queryHash = "replacement"))).relatedSourceFor(bound))
    }

    @Test
    fun `search and creator viewer retain liked seeds until related shelf dismissal`() {
        val liked = post("liked")
        val snapshot = ViewerFeedSnapshot(
            queryHash = "active",
            results = listOf(liked),
            retainedLikedPostIds = setOf(liked.id),
        )
        val state = ViewerRouteLiveSourceState(
            feeds = mapOf(ViewerStreamSource.SEARCH to snapshot, ViewerStreamSource.CREATOR_PROFILE to snapshot),
            likedPostIds = setOf(liked.id),
            savedPostIds = setOf(liked.id),
        )
        listOf(ViewerStreamSource.SEARCH, ViewerStreamSource.CREATOR_PROFILE).forEach { source ->
            val session = session("active", source, SearchVisibilityFilters(hideLiked = true, hideSaved = true))
            assertEquals(listOf(liked), state.visiblePostsFor(session))
            assertEquals(emptyList<Post>(), state.copy(
                feeds = state.feeds.mapValues { (_, feed) -> feed.copy(retainedLikedPostIds = emptySet()) },
            ).visiblePostsFor(session))
        }
    }

    @Test
    fun `related viewer has no live source and records ordinary watched membership`() {
        val state = ViewerRouteLiveSourceState()
        val context = ViewerLaunchContext(
            queryHash = "related:PIXIV:1",
            startIndex = 0,
            streamSource = ViewerStreamSource.RELATED,
            scrollOffsetHint = 0,
        )

        assertNull(state.forSource(ViewerStreamSource.RELATED))
        assertEquals(RecentPostSection.WATCHED, recentPostSectionForViewer(context))
        assertNull(
            state.visiblePostsFor(
                ViewerSession(
                    posts = listOf(post("related")),
                    context = context,
                    liveSearchBinding = false,
                )
            )
        )
    }

    @Test
    fun `Recents Viewer records the explicit section while direct launches derive it`() {
        val recentsCodex = ViewerLaunchContext(
            queryHash = "recents:codex",
            startIndex = 0,
            streamSource = ViewerStreamSource.RECENTS,
            scrollOffsetHint = 0,
            recentsSection = RecentPostSection.CODEX,
        )

        assertEquals(RecentPostSection.CODEX, recentPostSectionForViewer(recentsCodex))
        assertEquals(
            RecentPostSection.CODEX,
            recentPostSectionForViewer(recentsCodex.copy(streamSource = ViewerStreamSource.CODEX)),
        )
        assertEquals(
            RecentPostSection.WATCHED,
            recentPostSectionForViewer(recentsCodex.copy(streamSource = ViewerStreamSource.SEARCH, recentsSection = null)),
        )
        assertEquals(
            RecentPostSection.WATCHED,
            recentPostSectionForViewer(recentsCodex.copy(streamSource = ViewerStreamSource.FOR_YOU, recentsSection = null)),
        )
    }

    @Test
    fun `History stores a post watched in a trail as a search`() {
        val trail = ViewerLaunchContext("trail", 0, ViewerStreamSource.TRAIL, 0)

        assertEquals(ViewerStreamSource.SEARCH, historyOrigin(trail))
        assertEquals(ViewerStreamSource.CODEX, historyOrigin(trail.copy(streamSource = ViewerStreamSource.CODEX)))
        assertEquals(ViewerStreamSource.SEARCH, historyOrigin(null))
    }

    @Test
    fun `live source requires exact query identity and reapplies search visibility`() {
        val liked = post("liked")
        val saved = post("saved")
        val watched = post("watched")
        val visible = post("visible")
        val session = session(
            queryHash = "search:active",
            source = ViewerStreamSource.SEARCH,
            filters = SearchVisibilityFilters(
                hideLiked = true,
                hideSaved = true,
                hideWatched = true,
                animatedDurationRange = AnimatedDurationRange.Full,
            ),
        )
        val state = ViewerRouteLiveSourceState(
            feeds = mapOf(ViewerStreamSource.SEARCH to ViewerFeedSnapshot(
                queryHash = "search:active",
                results = listOf(liked, saved, watched, visible),
            )),
            likedPostIds = setOf(liked.id),
            savedPostIds = setOf(saved.id),
            watchedPostIds = setOf(watched.id),
        )

        assertEquals(listOf(visible), state.visiblePostsFor(session))
        assertNull(
            state.copy(
                feeds = state.feeds.mapValues { (_, feed) -> feed.copy(queryHash = "search:replacement") },
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
            feeds = mapOf(ViewerStreamSource.FOR_YOU to ViewerFeedSnapshot(
                queryHash = "for_you:seed",
                results = listOf(liked),
                canLoadMore = true,
            )),
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
    fun `the shell's Viewer slot empties when the Viewer's owner is cleared`() {
        val owner = testViewerOwner(SavedStateHandle())
        val store = ViewModelStore()
        val provider = ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = owner as T
            },
        )
        provider[ViewerViewModel::class.java]
        val slot = RouteOwnerSlot<ViewerViewModel>().apply { publish(owner) }
        owner.replaceSession(session("search:owned", ViewerStreamSource.SEARCH))

        assertEquals("search:owned", slot.current?.session?.value?.context?.queryHash)

        store.clear()

        assertNull(slot.current)
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
