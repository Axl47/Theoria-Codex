package com.theoriacodex.app.creator

import androidx.lifecycle.SavedStateHandle
import com.theoriacodex.app.creator.state.CreatorAction
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.domain.adapter.Page
import com.theoriacodex.domain.model.AccountDetails
import com.theoriacodex.domain.model.AccountFeed
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CreatorAccountViewModelTest {
    private val account = CreatorProfile(SourceKey.GELBOORU, "Account", "42", uploadsQuery = "user:Account")

    @Test
    fun `an account loads its first feed without waiting for details`() = runTest {
        val engine = AccountEngine().apply { detailsGate = CompletableDeferred() }
        val viewModel = CreatorProfileViewModel(engine, SavedStateHandle(), coroutineScope = this)

        viewModel.onAction(CreatorAction.OpenCreator(account))
        runCurrent()

        assertEquals(listOf("uploads"), viewModel.state.value.results.map { it.id.sourcePostId })
        assertNull(viewModel.state.value.details)
        engine.detailsGate?.complete(Unit)
        advanceUntilIdle()
        assertEquals(AccountDetails(favoriteCount = 9), viewModel.state.value.details)
    }

    @Test
    fun `failed details leave the header bare and the page without an error`() = runTest {
        val engine = AccountEngine().apply { detailsFailure = IllegalStateException("profile gone") }
        val viewModel = CreatorProfileViewModel(engine, SavedStateHandle(), coroutineScope = this)

        viewModel.onAction(CreatorAction.OpenCreator(account))
        advanceUntilIdle()

        assertNull(viewModel.state.value.details)
        assertNull(viewModel.state.value.errorMessage)
        assertEquals(listOf("uploads"), viewModel.state.value.results.map { it.id.sourcePostId })
    }

    @Test
    fun `switching tabs cancels the left feed's request and loads the other with its own source feed`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val engine = AccountEngine().apply { uploadsGate = gate }
        val viewModel = CreatorProfileViewModel(engine, SavedStateHandle(), coroutineScope = this)
        viewModel.onAction(CreatorAction.OpenCreator(account))
        runCurrent()

        viewModel.onAction(CreatorAction.SelectFeed(AccountFeed.FAVORITES))
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(AccountFeed.FAVORITES, viewModel.state.value.selectedFeed)
        assertEquals(listOf("favorites"), viewModel.state.value.results.map { it.id.sourcePostId })
        assertEquals(listOf(AccountFeed.UPLOADS, AccountFeed.FAVORITES), engine.loadedFeeds)
    }

    @Test
    fun `only the uploads list is recorded as a visit`() = runTest {
        val engine = AccountEngine()
        val viewModel = CreatorProfileViewModel(engine, SavedStateHandle(), coroutineScope = this)
        viewModel.onAction(CreatorAction.OpenCreator(account))
        advanceUntilIdle()

        viewModel.onAction(CreatorAction.SelectFeed(AccountFeed.FAVORITES))
        advanceUntilIdle()

        assertEquals(listOf(account), engine.visits)
    }

    @Test
    fun `a creator without feeds loads uploads through the same path and asks for no details`() = runTest {
        val engine = AccountEngine().apply { feeds = emptyList() }
        val viewModel = CreatorProfileViewModel(engine, SavedStateHandle(), coroutineScope = this)

        viewModel.onAction(CreatorAction.OpenCreator(account))
        advanceUntilIdle()

        assertEquals(emptyList<AccountFeed>(), viewModel.state.value.feeds)
        assertEquals(0, engine.detailsRequests)
        assertEquals(listOf(AccountFeed.UPLOADS), engine.loadedFeeds)
    }
}

/** A source whose account has uploads and favorites; each feed returns one post named after it. */
private class AccountEngine : CreatorRouteEngine {
    var feeds = listOf(AccountFeed.UPLOADS, AccountFeed.FAVORITES)
    var detailsGate: CompletableDeferred<Unit>? = null
    var detailsFailure: Throwable? = null
    var uploadsGate: CompletableDeferred<Unit>? = null
    var detailsRequests = 0
    val loadedFeeds = mutableListOf<AccountFeed>()
    val visits = mutableListOf<CreatorProfile>()

    override fun supports(creator: CreatorProfile): Boolean = true
    override fun accountFeeds(creator: CreatorProfile): List<AccountFeed> = feeds

    override suspend fun accountDetails(creator: CreatorProfile): AccountDetails? {
        detailsRequests++
        detailsGate?.await()
        detailsFailure?.let { throw it }
        return AccountDetails(favoriteCount = 9)
    }

    override suspend fun load(creator: CreatorProfile, feed: AccountFeed, pageToken: String?): Page<Post> {
        loadedFeeds += feed
        if (feed == AccountFeed.UPLOADS) uploadsGate?.await()
        return Page(listOf(testPost(sourcePostId = feed.name.lowercase())), null)
    }

    override suspend fun recordAcceptedVisit(creator: CreatorProfile, posts: List<Post>) {
        visits += creator
    }

    override suspend fun resolvePost(postId: PostId): Post? = null
}
