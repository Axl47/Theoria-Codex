package com.theoriacodex.app.search

import com.theoriacodex.app.search.state.SearchAction
import com.theoriacodex.app.search.state.SearchSourceScope
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.SourceWeightSettings
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
internal class UnifiedWeightsSearchViewModelTest : SearchViewModelTestFixture() {
    @Test
    fun `returning with changed weights applies pending unified selection exactly once`() = runTest(mainDispatcherRule.dispatcher) {
        val pixiv = ViewModelSearchAdapter(SourceKey.PIXIV)
        val gelbooru = ViewModelSearchAdapter(SourceKey.GELBOORU)
        val owner = viewModel(pixiv, additionalAdapters = listOf(gelbooru))
        restore(owner)
        val original = AppSettings()
        owner.synchronizeEnvironment(original)
        owner.onAction(SearchAction.SelectMode(QueryMode.Source(SourceKey.PIXIV)))
        owner.onAction(SearchAction.ApplyDraft)
        advanceUntilIdle()
        owner.onAction(SearchAction.ToggleTemporarySource(SourceKey.GELBOORU))
        val group = setOf(SourceKey.PIXIV, SourceKey.GELBOORU)
        val updated = original.copy(runtime = SourceWeightSettings.updateWeights(
            original.runtime, group, mapOf(SourceKey.PIXIV to 0.2, SourceKey.GELBOORU to 0.8),
        ))
        val previousPixivRequests = pixiv.searchCount
        owner.synchronizeEnvironment(updated)
        advanceUntilIdle()

        assertEquals(SearchSourceScope.fromSources(group), owner.state.value.query.appliedSourceScope)
        assertFalse(owner.state.value.hasPendingChanges)
        assertEquals(previousPixivRequests + 1, pixiv.searchCount)
        assertEquals(1, gelbooru.searchCount)
        owner.synchronizeEnvironment(updated)
        advanceUntilIdle()
        assertEquals(previousPixivRequests + 1, pixiv.searchCount)
        assertEquals(1, gelbooru.searchCount)
    }

    @Test
    fun `changed weights refresh an already applied group`() = runTest(mainDispatcherRule.dispatcher) {
        val pixiv = ViewModelSearchAdapter(SourceKey.PIXIV)
        val gelbooru = ViewModelSearchAdapter(SourceKey.GELBOORU)
        val owner = viewModel(pixiv, additionalAdapters = listOf(gelbooru))
        restore(owner)
        val original = AppSettings()
        owner.synchronizeEnvironment(original)
        owner.onAction(SearchAction.SelectMode(QueryMode.Source(SourceKey.PIXIV)))
        owner.onAction(SearchAction.ToggleTemporarySource(SourceKey.GELBOORU))
        owner.onAction(SearchAction.ApplyDraft)
        advanceUntilIdle()
        val before = pixiv.searchCount
        val group = setOf(SourceKey.PIXIV, SourceKey.GELBOORU)
        owner.synchronizeEnvironment(original.copy(runtime = SourceWeightSettings.updateWeights(
            original.runtime, group, mapOf(SourceKey.PIXIV to 0.1, SourceKey.GELBOORU to 0.9),
        )))
        advanceUntilIdle()
        assertEquals(before + 1, pixiv.searchCount)
        assertEquals(before + 1, gelbooru.searchCount)
    }
}
