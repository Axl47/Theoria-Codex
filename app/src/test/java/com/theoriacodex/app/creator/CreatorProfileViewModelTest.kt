package com.theoriacodex.app.creator

import androidx.lifecycle.SavedStateHandle
import com.theoriacodex.app.creator.state.CreatorAction
import com.theoriacodex.domain.adapter.Page
import com.theoriacodex.app.creator.state.CreatorEffect
import com.theoriacodex.app.creator.state.CreatorFailureReason
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CreatorProfileViewModelTest {
    @Test
    fun `saved creator inputs reconstruct route and load first page`() = runTest {
        val creator = creator(SourceKey.IWARA, "iwara-user")
        val savedState = savedCreator(creator)
        val engine = FakeCreatorRouteEngine().apply {
            openResults = listOf(testPost(source = SourceKey.IWARA, sourcePostId = "video"))
        }

        val viewModel = CreatorProfileViewModel(engine, savedState, coroutineScope = this)
        advanceUntilIdle()

        assertEquals(listOf(creator), engine.openedCreators)
        assertEquals(creator, viewModel.state.value.creator)
        assertEquals(listOf("video"), viewModel.state.value.results.map { it.id.sourcePostId })
    }

    @Test
    fun `opening a replacement creator owns new identity and saved inputs`() = runTest {
        val first = creator(SourceKey.PIXIV, "first")
        val replacement = creator(SourceKey.IWARA, "second")
        val savedState = SavedStateHandle()
        val engine = FakeCreatorRouteEngine()
        val viewModel = CreatorProfileViewModel(engine, savedState, coroutineScope = this)

        viewModel.onAction(CreatorAction.OpenCreator(first))
        advanceUntilIdle()
        engine.openResults = listOf(testPost(source = SourceKey.IWARA, sourcePostId = "new"))
        viewModel.onAction(CreatorAction.OpenCreator(replacement))
        advanceUntilIdle()

        assertEquals(replacement, viewModel.state.value.creator)
        assertEquals(listOf("new"), viewModel.state.value.results.map { it.id.sourcePostId })
        assertEquals(SourceKey.IWARA.name, savedState.get<String>(CreatorProfileViewModel.KEY_SOURCE))
        assertEquals("second", savedState.get<String>(CreatorProfileViewModel.KEY_PROFILE_ID))
    }

    @Test
    fun `capability loss clears results and surfaces unsupported source`() = runTest {
        val creator = creator(SourceKey.PIXIV, "creator")
        val engine = FakeCreatorRouteEngine().apply {
            openResults = listOf(testPost(sourcePostId = "first"))
        }
        val viewModel = CreatorProfileViewModel(engine, SavedStateHandle(), coroutineScope = this)
        viewModel.onAction(CreatorAction.OpenCreator(creator))
        advanceUntilIdle()

        engine.capabilityAvailable = false
        viewModel.onSourceAvailabilityChanged()

        assertTrue(viewModel.state.value.results.isEmpty())
        assertEquals(CreatorFailureReason.UNSUPPORTED_SOURCE, viewModel.state.value.failureReason)
        assertFalse(viewModel.state.value.isRefreshing)
    }

    @Test
    fun `unchanged capability observation does not cancel initial creator load`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val creator = creator(SourceKey.PIXIV, "creator")
        val engine = FakeCreatorRouteEngine().apply {
            firstOpenStarted = started
            firstOpenRelease = release
            firstOpenResults = listOf(testPost(sourcePostId = "loaded"))
        }
        val viewModel = CreatorProfileViewModel(engine, SavedStateHandle(), coroutineScope = this)

        viewModel.onAction(CreatorAction.OpenCreator(creator))
        runCurrent()
        started.await()
        viewModel.onSourceAvailabilityChanged()
        release.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("loaded"), viewModel.state.value.results.map { it.id.sourcePostId })
        assertFalse(viewModel.state.value.isRefreshing)
    }

    @Test
    fun `creator replacement cancels old owner and rejects late completion`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = creator(SourceKey.PIXIV, "first")
        val replacement = creator(SourceKey.IWARA, "replacement")
        val engine = FakeCreatorRouteEngine().apply {
            firstOpenStarted = started
            firstOpenRelease = release
            firstOpenResults = listOf(testPost(sourcePostId = "stale"))
            openResults = listOf(testPost(source = SourceKey.IWARA, sourcePostId = "fresh"))
        }
        val viewModel = CreatorProfileViewModel(engine, SavedStateHandle(), coroutineScope = this)

        viewModel.onAction(CreatorAction.OpenCreator(first))
        runCurrent()
        started.await()
        viewModel.onAction(CreatorAction.OpenCreator(replacement))
        runCurrent()
        assertEquals(listOf("fresh"), viewModel.state.value.results.map { it.id.sourcePostId })

        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(replacement, viewModel.state.value.creator)
        assertEquals(listOf("fresh"), viewModel.state.value.results.map { it.id.sourcePostId })
        assertEquals(listOf(replacement), engine.visits)
    }

    @Test
    fun `paging failure retries exact continuation without duplicating accepted root bookkeeping`() = runTest {
        val first = testPost(sourcePostId = "first")
        val engine = FakeCreatorRouteEngine().apply {
            openResults = listOf(first)
            openCanLoadMore = true
            pageError = "page failed"
        }
        val viewModel = CreatorProfileViewModel(engine, SavedStateHandle(), coroutineScope = this)
        viewModel.onAction(CreatorAction.OpenCreator(creator(SourceKey.PIXIV, "creator")))
        advanceUntilIdle()

        viewModel.onAction(CreatorAction.LoadNextPage)
        advanceUntilIdle()

        assertEquals(listOf(first), viewModel.state.value.results)
        assertEquals("page failed", viewModel.state.value.errorMessage)
        assertFalse(viewModel.state.value.isPaging)
        assertTrue(viewModel.state.value.canLoadMore)
        viewModel.onAction(CreatorAction.LoadNextPage)
        advanceUntilIdle()
        assertEquals(listOf("next"), engine.pageTokens)
        engine.pageError = null
        engine.pageResults = listOf(first, testPost(sourcePostId = "next"))
        viewModel.onAction(CreatorAction.RetryPage)
        advanceUntilIdle()
        assertEquals(listOf("next", "next"), engine.pageTokens)
        assertEquals(listOf("first", "next"), viewModel.state.value.results.map { it.id.sourcePostId })
        assertEquals(1, engine.visits.size)
        assertNull(viewModel.state.value.errorMessage)
        assertFalse(viewModel.state.value.canLoadMore)
    }

    @Test
    fun `viewer and back intents are buffered shell effects`() = runTest {
        val post = testPost(sourcePostId = "first")
        val engine = FakeCreatorRouteEngine().apply { openResults = listOf(post) }
        val viewModel = CreatorProfileViewModel(engine, SavedStateHandle(), coroutineScope = this)
        viewModel.onAction(CreatorAction.OpenCreator(creator(SourceKey.PIXIV, "creator")))
        advanceUntilIdle()

        val openEffect = async { viewModel.effects.first() }
        viewModel.onAction(CreatorAction.OpenResult(index = 0, scrollOffsetHint = 8))
        val open = openEffect.await() as CreatorEffect.OpenViewer
        assertEquals(listOf(post), open.posts)
        assertEquals(8, open.context.scrollOffsetHint)

        val backEffect = async { viewModel.effects.first() }
        viewModel.onAction(CreatorAction.Back)
        assertEquals(CreatorEffect.NavigateBack, backEffect.await())
    }

    private fun creator(source: SourceKey, profileId: String): CreatorProfile {
        return CreatorProfile(
            source = source,
            displayName = "Creator $profileId",
            profileId = profileId,
            profileUrl = "https://example.test/$profileId",
            uploadsQuery = profileId,
        )
    }

    private fun savedCreator(creator: CreatorProfile): SavedStateHandle {
        return SavedStateHandle(
            mapOf(
                CreatorProfileViewModel.KEY_SOURCE to creator.source.name,
                CreatorProfileViewModel.KEY_DISPLAY_NAME to creator.displayName,
                CreatorProfileViewModel.KEY_PROFILE_ID to creator.profileId,
                CreatorProfileViewModel.KEY_PROFILE_URL to creator.profileUrl,
                CreatorProfileViewModel.KEY_UPLOADS_QUERY to creator.uploadsQuery,
            )
        )
    }
}

private class FakeCreatorRouteEngine : CreatorRouteEngine {
    val openedCreators = mutableListOf<CreatorProfile>()
    val pageTokens = mutableListOf<String>()
    val visits = mutableListOf<CreatorProfile>()
    var openResults: List<Post> = emptyList()
    var openCanLoadMore = false
    var pageResults: List<Post> = emptyList()
    var pageError: String? = null
    var capabilityAvailable = true
    var firstOpenStarted: CompletableDeferred<Unit>? = null
    var firstOpenRelease: CompletableDeferred<Unit>? = null
    var firstOpenResults: List<Post> = emptyList()

    override fun supports(creator: CreatorProfile): Boolean = capabilityAvailable

    override suspend fun load(creator: CreatorProfile, pageToken: String?): Page<Post> {
        if (pageToken != null) {
            pageTokens += pageToken
            pageError?.let { error(it) }
            return Page(pageResults, null)
        }
        openedCreators += creator
        val started = firstOpenStarted
        val release = firstOpenRelease
        if (openedCreators.size == 1 && started != null && release != null) {
            withContext(NonCancellable) {
                started.complete(Unit)
                release.await()
            }
            return Page(firstOpenResults, null)
        }
        return Page(openResults, if (openCanLoadMore) "next" else null)
    }

    override suspend fun recordAcceptedVisit(creator: CreatorProfile, posts: List<Post>) {
        visits += creator
    }

    override suspend fun resolvePost(postId: PostId): Post? = null
}
