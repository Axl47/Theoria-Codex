package com.theoriacodex.app.creator.state

import com.theoriacodex.app.testing.testPost
import com.theoriacodex.domain.adapter.Page
import com.theoriacodex.domain.model.AccountDetails
import com.theoriacodex.domain.model.AccountFeed
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountFeedStateTest {
    private val account = CreatorProfile(SourceKey.GELBOORU, "Account", "42", uploadsQuery = "user:Account")
    private val bothFeeds = listOf(AccountFeed.UPLOADS, AccountFeed.FAVORITES)

    @Test
    fun `an account opens on its first feed with the uploads key it always had (ADR 0066)`() {
        val opened = CreatorUiState().reduce(CreatorAction.OpenCreator(account, bothFeeds))
        assertEquals(AccountFeed.UPLOADS, opened.state.selectedFeed)
        assertEquals(creatorQueryHash(account), opened.state.queryHash)
        assertEquals("creator:GELBOORU:user:Account", opened.state.queryHash)
        val load = opened.effect as CreatorEffect.LoadPage
        assertEquals(AccountFeed.UPLOADS, load.feed)
        assertEquals("creator:GELBOORU:user:Account:favorites", creatorQueryHash(account, AccountFeed.FAVORITES))

        val favoritesFirst = CreatorUiState()
            .reduce(CreatorAction.OpenCreator(account, listOf(AccountFeed.FAVORITES, AccountFeed.UPLOADS)))
        assertEquals(AccountFeed.FAVORITES, favoritesFirst.state.selectedFeed)
        assertEquals("creator:GELBOORU:user:Account:favorites", favoritesFirst.state.queryHash)
        assertEquals(AccountFeed.FAVORITES, (favoritesFirst.effect as CreatorEffect.LoadPage).feed)
    }

    @Test
    fun `switching tabs parks the list and restores it without a request (ADR 0066)`() {
        val uploads = listOf(testPost(sourcePostId = "u1"))
        val onFavorites = loaded(uploads, "uploads-2").reduce(CreatorAction.SelectFeed(AccountFeed.FAVORITES))
        val favoritesLoad = onFavorites.effect as CreatorEffect.LoadPage
        assertEquals(AccountFeed.FAVORITES, favoritesLoad.feed)
        assertNull(favoritesLoad.pageToken)
        assertEquals("creator:GELBOORU:user:Account:favorites", onFavorites.state.queryHash)
        assertTrue(onFavorites.state.results.isEmpty())
        assertTrue(onFavorites.state.isRefreshing)

        val favorites = listOf(testPost(sourcePostId = "f1"))
        val shown = onFavorites.state.reduce(CreatorAction.PageLoaded(favoritesLoad.request, Page(favorites, "fav-2"))).state
        val back = shown.reduce(CreatorAction.SelectFeed(AccountFeed.UPLOADS))
        assertNull(back.effect)
        assertEquals(uploads, back.state.results)
        assertEquals("uploads-2", back.state.nextPageToken)
        assertEquals(favorites, back.state.parkedFeeds.getValue(AccountFeed.FAVORITES).results)
    }

    @Test
    fun `follow waits for the uploads list even after switching tabs mid-load (ADR 0066)`() {
        val opened = CreatorUiState().reduce(CreatorAction.OpenCreator(account, bothFeeds)).state
        assertTrue(opened.uploadsRefreshing)
        assertTrue(opened.reduce(CreatorAction.SelectFeed(AccountFeed.FAVORITES)).state.uploadsRefreshing)
        assertFalse(loaded(listOf(testPost(sourcePostId = "u1")), null).reduce(CreatorAction.SelectFeed(AccountFeed.FAVORITES)).state.uploadsRefreshing)
        assertTrue(CreatorUiState().reduce(CreatorAction.OpenCreator(account, listOf(AccountFeed.FAVORITES))).state.uploadsRefreshing)
    }

    @Test
    fun `each feed pages with its own continuation`() {
        val onFavorites = loaded(listOf(testPost(sourcePostId = "u1")), "uploads-2")
            .reduce(CreatorAction.SelectFeed(AccountFeed.FAVORITES))
        val first = onFavorites.effect as CreatorEffect.LoadPage
        val shown = onFavorites.state.reduce(
            CreatorAction.PageLoaded(first.request, Page(listOf(testPost(sourcePostId = "f1")), "fav-2")),
        ).state

        val next = shown.reduce(CreatorAction.LoadNextPage).effect as CreatorEffect.LoadPage
        assertEquals(AccountFeed.FAVORITES, next.feed)
        assertEquals("fav-2", next.pageToken)

        val uploadsPage = shown.reduce(CreatorAction.SelectFeed(AccountFeed.UPLOADS)).state
            .reduce(CreatorAction.LoadNextPage).effect as CreatorEffect.LoadPage
        assertEquals(AccountFeed.UPLOADS, uploadsPage.feed)
        assertEquals("uploads-2", uploadsPage.pageToken)
    }

    @Test
    fun `a request outlived by a tab switch is ignored (ADR 0066)`() {
        val opened = CreatorUiState().reduce(CreatorAction.OpenCreator(account, bothFeeds))
        val uploadsRequest = (opened.effect as CreatorEffect.LoadPage).request
        val switched = opened.state.reduce(CreatorAction.SelectFeed(AccountFeed.FAVORITES)).state

        assertEquals(switched, switched.reduce(CreatorAction.PageLoaded(uploadsRequest, Page(listOf(testPost()), null))).state)
        assertEquals(switched, switched.reduce(CreatorAction.RequestFailed(uploadsRequest, "late")).state)
        assertEquals(switched, switched.reduce(CreatorAction.RequestCancelled(uploadsRequest)).state)

        val settled = opened.state.reduce(CreatorAction.RequestFailed(uploadsRequest, "failed")).state
        assertEquals(settled, settled.reduce(CreatorAction.RequestCancelled(uploadsRequest)).state)

        val onFavorites = loaded(listOf(testPost(sourcePostId = "u1")), "uploads-2").reduce(CreatorAction.SelectFeed(AccountFeed.FAVORITES))
        val favoritesRequest = (onFavorites.effect as CreatorEffect.LoadPage).request
        val favoritesShown = onFavorites.state
            .reduce(CreatorAction.PageLoaded(favoritesRequest, Page(listOf(testPost(sourcePostId = "f1")), "fav-2"))).state
        val paging = favoritesShown.reduce(CreatorAction.LoadNextPage)
        val pageRequest = (paging.effect as CreatorEffect.LoadPage).request
        val backOnUploads = paging.state.reduce(CreatorAction.SelectFeed(AccountFeed.UPLOADS)).state
        assertEquals(
            backOnUploads,
            backOnUploads.reduce(CreatorAction.PageLoaded(pageRequest, Page(listOf(testPost(sourcePostId = "f2")), null))).state,
        )
    }

    @Test
    fun `a feed left mid-load reloads when selected again, a failed one keeps its error (ADR 0066)`() {
        val opened = CreatorUiState().reduce(CreatorAction.OpenCreator(account, bothFeeds)).state
        val away = opened.reduce(CreatorAction.SelectFeed(AccountFeed.FAVORITES))
        val failedRequest = (away.effect as CreatorEffect.LoadPage).request
        val failed = away.state.reduce(CreatorAction.RequestFailed(failedRequest, "network failed")).state

        val backToUploads = failed.reduce(CreatorAction.SelectFeed(AccountFeed.UPLOADS))
        assertEquals(AccountFeed.UPLOADS, (backToUploads.effect as CreatorEffect.LoadPage).feed)
        val backToFavorites = backToUploads.state.reduce(CreatorAction.SelectFeed(AccountFeed.FAVORITES))
        assertNull(backToFavorites.effect)
        assertEquals("network failed", backToFavorites.state.errorMessage)
    }

    @Test
    fun `a feed that finished empty is not reloaded when selected again (ADR 0066)`() {
        val onFavorites = loaded(listOf(testPost(sourcePostId = "u1")), null).reduce(CreatorAction.SelectFeed(AccountFeed.FAVORITES))
        val request = (onFavorites.effect as CreatorEffect.LoadPage).request
        val settledEmpty = onFavorites.state.reduce(CreatorAction.PageLoaded(request, Page(emptyList(), null))).state
        val parked = settledEmpty.reduce(CreatorAction.SelectFeed(AccountFeed.UPLOADS)).state

        val back = parked.reduce(CreatorAction.SelectFeed(AccountFeed.FAVORITES))
        assertNull(back.effect)
        assertEquals(CreatorEmptyReason.NO_RESULTS, back.state.emptyReason)
    }

    @Test
    fun `Favorites is hidden once the source reports none and unknown counts keep it (ADR 0066)`() {
        val opened = CreatorUiState().reduce(CreatorAction.OpenCreator(account, bothFeeds)).state
        assertEquals(bothFeeds, opened.tabs)
        assertEquals(bothFeeds, opened.reduce(CreatorAction.DetailsLoaded(account, AccountDetails(uploadCount = 3))).state.tabs)
        val hidden = opened.reduce(CreatorAction.DetailsLoaded(account, AccountDetails(favoriteCount = 0))).state
        assertEquals(listOf(AccountFeed.UPLOADS), hidden.tabs)
        assertEquals(bothFeeds, opened.reduce(CreatorAction.DetailsLoaded(account, AccountDetails(favoriteCount = 5))).state.tabs)
        assertEquals(hidden, hidden.reduce(CreatorAction.SelectFeed(AccountFeed.FAVORITES)).state)
    }

    @Test
    fun `details that hide the selected Favorites tab return to uploads (ADR 0066)`() {
        val onFavorites = loaded(listOf(testPost(sourcePostId = "u1")), null)
            .reduce(CreatorAction.SelectFeed(AccountFeed.FAVORITES)).state
        val hidden = onFavorites.reduce(CreatorAction.DetailsLoaded(account, AccountDetails(favoriteCount = 0)))
        assertEquals(AccountFeed.UPLOADS, hidden.state.selectedFeed)
        assertEquals(listOf("u1"), hidden.state.results.map { it.id.sourcePostId })
        assertNull(hidden.effect)
    }

    @Test
    fun `details for a replaced creator are dropped`() {
        val opened = CreatorUiState().reduce(CreatorAction.OpenCreator(account, bothFeeds)).state
        val replaced = opened.reduce(CreatorAction.OpenCreator(account.copy(uploadsQuery = "user:Other"), bothFeeds)).state
        assertEquals(replaced, replaced.reduce(CreatorAction.DetailsLoaded(account, AccountDetails(favoriteCount = 0))).state)
        assertNull(replaced.details)
    }

    @Test
    fun `Follow reads uploads while another feed is selected (ADR 0066)`() {
        val uploads = listOf(testPost(sourcePostId = "u1"))
        val state = loaded(uploads, null)
        assertEquals(uploads, state.uploads)
        val onFavorites = state.reduce(CreatorAction.SelectFeed(AccountFeed.FAVORITES)).state
        assertEquals(uploads, onFavorites.uploads)
        assertFalse(onFavorites.uploadsRefreshing)
    }

    @Test
    fun `a creator that is not an account has no tabs and ignores feed selection (ADR 0066)`() {
        val opened = CreatorUiState().reduce(CreatorAction.OpenCreator(account)).state
        assertTrue(opened.feeds.isEmpty())
        assertTrue(opened.tabs.isEmpty())
        assertEquals(opened, opened.reduce(CreatorAction.SelectFeed(AccountFeed.FAVORITES)).state)
        assertEquals("creator:GELBOORU:user:Account", opened.queryHash)
    }

    @Test
    fun `a reconnect lets a parked feed that failed load again when shown`() {
        val onFavorites = loaded(listOf(testPost(sourcePostId = "u1")), null).reduce(CreatorAction.SelectFeed(AccountFeed.FAVORITES))
        val request = (onFavorites.effect as CreatorEffect.LoadPage).request
        val parked = onFavorites.state.reduce(CreatorAction.RequestFailed(request, "offline")).state
            .reduce(CreatorAction.SelectFeed(AccountFeed.UPLOADS)).state

        assertNull(parked.reduce(CreatorAction.SelectFeed(AccountFeed.FAVORITES)).effect)
        val reload = parked.reduce(CreatorAction.Reconnected).state.reduce(CreatorAction.SelectFeed(AccountFeed.FAVORITES))
        assertEquals(AccountFeed.FAVORITES, (reload.effect as CreatorEffect.LoadPage).feed)
    }

    @Test
    fun `a reconnect does not retry an account failure`() {
        val opened = CreatorUiState().reduce(CreatorAction.OpenCreator(account, bothFeeds))
        val request = (opened.effect as CreatorEffect.LoadPage).request
        val failed = opened.state.reduce(CreatorAction.RequestFailed(request, "sign in", CreatorFailureReason.ACCOUNT)).state

        assertNull(failed.reduce(CreatorAction.Reconnected).effect)
        val networkFailure = opened.state.reduce(CreatorAction.RequestFailed(request, "offline")).state
        assertTrue(networkFailure.reduce(CreatorAction.Reconnected).effect is CreatorEffect.LoadPage)
    }

    private fun loaded(posts: List<Post>, token: String?): CreatorUiState {
        val opened = CreatorUiState().reduce(CreatorAction.OpenCreator(account, bothFeeds))
        val request = (opened.effect as CreatorEffect.LoadPage).request
        return opened.state.reduce(CreatorAction.PageLoaded(request, Page(posts, token))).state
    }
}
