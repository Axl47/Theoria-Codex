package com.theoriacodex.app.search

import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.InMemoryQueryRepository
import com.theoriacodex.data.repository.InMemorySettingsRepository
import com.theoriacodex.data.repository.InMemoryUiRestoreRepository
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.UnifiedSearchOrchestrator
import com.theoriacodex.stubs.StubAdapterRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchCoordinatorTest {
    @Test
    fun `draft apply reset transitions preserve explicit apply semantics`() = runTest {
        val coordinator = coordinator()
        coordinator.initialize()

        coordinator.addIncludeTag("landscape")
        assertTrue(coordinator.hasPendingChanges)
        assertTrue("landscape" in coordinator.draftQuery.includeTags)
        assertTrue(coordinator.appliedQuery.includeTags.isEmpty())

        coordinator.resetDraft()
        assertFalse(coordinator.hasPendingChanges)
        assertTrue(coordinator.draftQuery.includeTags.isEmpty())

        coordinator.addIncludeTag("landscape")
        coordinator.applyDraft()
        assertFalse(coordinator.hasPendingChanges)
        assertTrue("landscape" in coordinator.appliedQuery.includeTags)
    }

    @Test
    fun `query hash keyed scroll state and viewer launch context are restored`() = runTest {
        val coordinator = coordinator()
        coordinator.initialize()
        coordinator.setMode(QueryMode.Source(SourceKey.PIXIV))
        coordinator.addIncludeTag("portrait")
        coordinator.applyDraft()

        coordinator.persistSearchScrollState(index = 4, offsetPx = 120)
        val restored = coordinator.restoreSearchScrollState()
        val context = coordinator.buildViewerLaunchContext(startIndex = 2, scrollOffsetHint = 120)

        assertEquals(4, restored?.firstVisibleItemIndex)
        assertEquals(120, restored?.firstVisibleItemOffsetPx)
        assertEquals(coordinator.appliedQueryHash, context.queryHash)
        assertEquals(2, context.startIndex)
    }

    @Test
    fun `runtime source settings change requests refresh only after at least one execution`() = runTest {
        val coordinator = coordinator()
        coordinator.initialize()
        val firstChange = coordinator.onSettingsChanged(
            AppSettings(
                runtime = AppSettings().runtime.copy(
                    enabledSources = setOf(SourceKey.PIXIV),
                )
            )
        )
        assertFalse(firstChange)

        coordinator.applyDraft()
        val secondChange = coordinator.onSettingsChanged(
            AppSettings(
                runtime = AppSettings().runtime.copy(
                    enabledSources = setOf(SourceKey.PIXIV, SourceKey.GELBOORU),
                )
            )
        )
        assertTrue(secondChange)
        assertNotNull(coordinator.statuses)
    }

    @Test
    fun `mode options and source mode availability follow registry`() = runTest {
        val coordinator = SearchCoordinator(
            registry = LimitedStubRegistry(setOf(SourceKey.PIXIV)),
            queryRepository = InMemoryQueryRepository(),
            settingsRepository = InMemorySettingsRepository(),
            uiRestoreRepository = InMemoryUiRestoreRepository(),
        )
        coordinator.initialize()

        assertEquals(
            listOf(QueryMode.Unified, QueryMode.Source(SourceKey.PIXIV)),
            coordinator.modeOptions,
        )

        coordinator.setMode(QueryMode.Source(SourceKey.GELBOORU))
        assertEquals(QueryMode.Unified, coordinator.draftQuery.mode)
    }

    private fun coordinator(): SearchCoordinator {
        return SearchCoordinator(
            registry = StubAdapterRegistry(),
            queryRepository = InMemoryQueryRepository(),
            settingsRepository = InMemorySettingsRepository(),
            uiRestoreRepository = InMemoryUiRestoreRepository(),
        )
    }
}

private class LimitedStubRegistry(
    private val available: Set<SourceKey>,
) : SourceAdapterRegistry {
    private val delegate = StubAdapterRegistry()
    private val adaptersBySource: Map<SourceKey, SourceAdapter> = available.associateWith { source ->
        requireNotNull(delegate.adapterFor(source))
    }

    override fun availableSources(): Set<SourceKey> = available

    override fun adapterFor(sourceKey: SourceKey): SourceAdapter? {
        return adaptersBySource[sourceKey]
    }

    override fun unifiedOrchestrator(): UnifiedSearchOrchestrator {
        return UnifiedSearchOrchestrator(adaptersBySource)
    }
}
