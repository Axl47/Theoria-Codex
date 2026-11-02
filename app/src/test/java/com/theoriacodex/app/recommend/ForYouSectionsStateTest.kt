package com.theoriacodex.app.recommend

import androidx.lifecycle.SavedStateHandle
import com.theoriacodex.app.recommend.state.ForYouAction
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.ForYouBlacklistEntry
import com.theoriacodex.data.repository.RecommendationProfile
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.SourceRunState
import com.theoriacodex.domain.orchestration.SourceRunStatus
import com.theoriacodex.domain.orchestration.UnifiedSearchResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
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
class ForYouSectionsStateTest {
    @Test fun `dismiss adopts live settings emissions without refreshing other rows and undo restores only that row`() = runTest {
        val engine = SectionsEngine().apply { sectionLoader = { listOf(section("forest"), section("city")) } }
        val owner = ForYouViewModel(engine, SavedStateHandle(), coroutineScope = this)
        engine.onSettings = { owner.synchronizeEnvironment(it, 3) }
        owner.synchronizeEnvironment(AppSettings(), 3)
        advanceUntilIdle()
        val original = owner.state.value.home
        val loads = engine.sectionLoads
        val roots = engine.requests.size
        owner.onAction(ForYouAction.DismissSection("PIXIV:forest"))
        advanceUntilIdle()
        assertEquals(listOf(original.sections.last()), owner.state.value.home.sections)
        assertEquals(original.mixedFeed, owner.state.value.home.mixedFeed)
        assertEquals(loads, engine.sectionLoads)
        assertEquals(roots, engine.requests.size)
        owner.onAction(ForYouAction.UndoSeedBlacklist(AppSettings().activeProfileId, engine.lastBlacklistEntries))
        advanceUntilIdle()
        assertEquals(original.sections, owner.state.value.home.sections)
        assertEquals(loads, engine.sectionLoads)
        assertEquals(roots, engine.requests.size)
    }

    @Test fun `save removes only its row after saving and retains the row on storage failure`() = runTest {
        val engine = SectionsEngine().apply { sectionLoader = { listOf(section("forest"), section("city")) } }
        val owner = ForYouViewModel(engine, SavedStateHandle(), coroutineScope = this)
        engine.onSettings = { owner.synchronizeEnvironment(it, 3) }
        owner.synchronizeEnvironment(AppSettings(), 3)
        advanceUntilIdle()
        engine.failPin = true
        owner.onAction(ForYouAction.SaveSection("PIXIV:forest"))
        advanceUntilIdle()
        assertEquals(2, owner.state.value.home.sections.size)
        assertTrue(engine.lastBlacklistEntries.isEmpty())
        engine.failPin = false
        val roots = engine.requests.size
        val loads = engine.sectionLoads
        owner.onAction(ForYouAction.SaveSection("PIXIV:forest"))
        advanceUntilIdle()
        assertEquals("PIXIV:forest", engine.savedSection?.id)
        assertEquals(listOf("city"), owner.state.value.home.sections.map { it.title })
        assertEquals(loads, engine.sectionLoads)
        assertEquals(roots, engine.requests.size)
    }

    @Test fun `new search appends one distinct row and reorder preserves cached pages without new requests`() = runTest {
        val engine = SectionsEngine().apply {
            sectionLoader = { request -> if (request.additionalSection) listOf(section("city")) else listOf(section("forest")) }
        }
        val owner = ForYouViewModel(engine, SavedStateHandle(), coroutineScope = this)
        owner.synchronizeEnvironment(AppSettings(), 3)
        advanceUntilIdle()
        val first = owner.state.value.home.sections.single()
        val roots = engine.requests.size
        owner.onAction(ForYouAction.NewSection)
        advanceUntilIdle()
        assertEquals(listOf(first.id, "PIXIV:city"), owner.state.value.home.sections.map { it.id })
        assertEquals(setOf(first.id), engine.sectionRequests.last().excludedSectionIds)
        assertEquals(roots, engine.requests.size)
        owner.onAction(ForYouAction.ReorderSections(listOf("PIXIV:city", first.id)))
        assertEquals(listOf("city", "forest"), owner.state.value.home.sections.map { it.title })
        assertEquals(first.feed, owner.state.value.home.sections.last().feed)
        assertEquals(2, engine.sectionLoads)
    }

    @Test fun `overview refresh reseeds mixed For You even while an interest is active`() = runTest {
        val engine = SectionsEngine()
        val owner = ForYouViewModel(engine, SavedStateHandle(), coroutineScope = this)
        owner.synchronizeEnvironment(AppSettings(), 3)
        advanceUntilIdle()
        owner.onAction(ForYouAction.OpenSection("PIXIV:forest"))
        owner.onAction(ForYouAction.ShowHome)
        owner.onAction(ForYouAction.RefreshSections)
        advanceUntilIdle()
        assertTrue(engine.requests.last().shuffle)
        assertNull(engine.requests.last().historicalSeed)
        assertEquals("forest", owner.state.value.home.activeSection?.title)
        assertEquals(listOf("mixed"), owner.state.value.home.mixedFeed?.seed?.values?.flatten())
        assertFalse(owner.state.value.home.mixedLoading)
    }

    @Test fun `shuffle waits for pending pins so completed storage is reflected in the section`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val engine = SectionsEngine().apply { pinGate = gate }
        val owner = ForYouViewModel(engine, SavedStateHandle(), coroutineScope = this)
        owner.synchronizeEnvironment(AppSettings(), 3)
        advanceUntilIdle()
        val id = owner.state.value.home.sections.single().id
        owner.onAction(ForYouAction.PinSection(id))
        runCurrent()
        val loads = engine.sectionLoads
        owner.onAction(ForYouAction.RefreshSections)
        runCurrent()
        assertEquals(loads, engine.sectionLoads)
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(owner.state.value.home.sections.single().pinned)
        assertTrue(owner.state.value.home.pendingSectionIds.isEmpty())
    }

    @Test fun `opening a preview retains the overview behind Viewer and its selected feed binding`() = runTest {
        val engine = SectionsEngine()
        val owner = ForYouViewModel(engine, SavedStateHandle(), coroutineScope = this)
        owner.synchronizeEnvironment(AppSettings(), 3)
        advanceUntilIdle()
        val selected = owner.state.value.home.sections.single()
        owner.onAction(ForYouAction.OpenSectionResult(selected.id, 0, selected.feed.page.items))
        assertTrue(owner.state.value.home.visible)
        assertEquals(selected.id, owner.state.value.home.activeSection?.id)
        assertEquals(selected.feed.seed, owner.state.value.seedSummaryBySource)
    }

    @Test fun `cached section opens immediately pages its own query and retains pages on return`() = runTest {
        val engine = SectionsEngine()
        val owner = ForYouViewModel(engine, SavedStateHandle(), coroutineScope = this)
        owner.synchronizeEnvironment(AppSettings(), 3)
        advanceUntilIdle()
        val section = owner.state.value.home.sections.single()
        owner.onAction(ForYouAction.OpenSection(section.id))
        assertFalse(owner.state.value.home.visible)
        assertEquals(section.feed.page.items, owner.state.value.results)
        assertEquals(1, engine.requests.size)
        owner.onAction(ForYouAction.LoadNextPage)
        advanceUntilIdle()
        assertEquals(listOf("forest"), engine.pages.single().queriesBySource.getValue(SourceKey.PIXIV).includeTags)
        assertEquals(2, owner.state.value.results.size)
        owner.onAction(ForYouAction.ShowHome)
        owner.onAction(ForYouAction.OpenMixedFeed)
        assertEquals(listOf("mixed"), owner.state.value.results.map { it.id.sourcePostId })
        owner.onAction(ForYouAction.ShowHome)
        owner.onAction(ForYouAction.OpenSection(section.id))
        assertEquals(2, owner.state.value.results.size)
        assertEquals(1, engine.requests.size)
    }

    @Test fun `sort and refresh retain selected interest while source selection returns to a mixed feed`() = runTest {
        val engine = SectionsEngine()
        val owner = ForYouViewModel(engine, SavedStateHandle(), coroutineScope = this)
        owner.synchronizeEnvironment(AppSettings(), 3)
        advanceUntilIdle()
        owner.onAction(ForYouAction.OpenSection(owner.state.value.home.sections.single().id))
        owner.onAction(ForYouAction.SelectSort(SortMode.TOP))
        advanceUntilIdle()
        val scoped = engine.requests.first { it.sortMode == SortMode.TOP && it.historicalSeed != null }
        assertEquals(mapOf(SourceKey.PIXIV to listOf("forest")), scoped.historicalSeed)
        owner.onAction(ForYouAction.Refresh())
        advanceUntilIdle()
        assertEquals(scoped.historicalSeed, engine.requests.last().historicalSeed)
        owner.onAction(ForYouAction.SelectSource(SourceKey.GELBOORU))
        advanceUntilIdle()
        assertNull(engine.requests.last().historicalSeed)
        assertEquals(SourceKey.GELBOORU, engine.requests.last().selectedSource)
        assertNull(owner.state.value.home.activeSection)
    }

    @Test fun `late old-profile previews cannot replace the new profile and count updates preserve sections`() = runTest {
        val old = CompletableDeferred<List<ForYouSection>>()
        val engine = SectionsEngine().apply {
            sectionLoader = { request ->
                if (request.settings.activeProfileId == AppSettings().activeProfileId) {
                    withContext(NonCancellable) { old.await() }
                } else listOf(section("new-profile"))
            }
        }
        val owner = ForYouViewModel(engine, SavedStateHandle(), coroutineScope = this)
        owner.synchronizeEnvironment(AppSettings(), 3)
        runCurrent()
        val settings = AppSettings(activeProfileId = "other", recommendationProfiles = listOf(RecommendationProfile("other", "Other")))
        owner.synchronizeEnvironment(settings, 2)
        runCurrent()
        old.complete(listOf(section("old-profile")))
        advanceUntilIdle()
        assertEquals("new-profile", owner.state.value.home.sections.single().title)
        val loads = engine.sectionLoads
        owner.synchronizeEnvironment(settings, 8)
        advanceUntilIdle()
        assertEquals(loads, engine.sectionLoads)
    }

    @Test fun `pin commits after storage succeeds and failed pin leaves section unchanged`() = runTest {
        val engine = SectionsEngine()
        val owner = ForYouViewModel(engine, SavedStateHandle(), coroutineScope = this)
        owner.synchronizeEnvironment(AppSettings(), 3)
        advanceUntilIdle()
        val id = owner.state.value.home.sections.single().id
        owner.onAction(ForYouAction.PinSection(id))
        advanceUntilIdle()
        assertTrue(owner.state.value.home.sections.single().pinned)
        engine.failPin = true
        owner.onAction(ForYouAction.PinSection(id))
        advanceUntilIdle()
        assertTrue(owner.state.value.home.sections.single().pinned)
        assertTrue(owner.state.value.home.pendingSectionIds.isEmpty())
    }
}

private class SectionsEngine : ForYouRouteEngine {
    val requests = mutableListOf<ForYouFeedRequest>()
    val pages = mutableListOf<ForYouContinuation>()
    var sectionLoads = 0
    val sectionRequests = mutableListOf<ForYouFeedRequest>()
    var savedSection: ForYouSection? = null
    var onSettings: (AppSettings) -> Unit = {}
    var lastBlacklistEntries: List<ForYouBlacklistEntry> = emptyList()
    private var settings = AppSettings()
    var failPin = false
    var pinGate: CompletableDeferred<Unit>? = null
    var sectionLoader: suspend (ForYouFeedRequest) -> List<ForYouSection> = { listOf(section("forest")) }
    override fun availableSources(settings: AppSettings) = listOf(SourceKey.PIXIV, SourceKey.GELBOORU)
    override suspend fun loadSections(request: ForYouFeedRequest): List<ForYouSection> {
        sectionLoads++
        sectionRequests += request
        return sectionLoader(request)
    }
    override suspend fun pinSection(section: ForYouSection, pinned: Boolean): String? {
        pinGate?.await()
        check(!failPin) { "Storage failed" }
        if (pinned) savedSection = section
        return if (pinned) "saved-id" else null
    }
    override suspend fun execute(request: ForYouFeedRequest): ForYouFeedResult {
        requests += request
        val seed = request.historicalSeed ?: mapOf((request.selectedSource ?: SourceKey.PIXIV) to listOf("mixed"))
        return feed(seed.values.flatten().single(), seed.keys.single(), request.sortMode)
    }
    override suspend fun loadPage(continuation: ForYouContinuation, sources: Set<SourceKey>): UnifiedSearchResult {
        pages += continuation
        return UnifiedSearchResult(listOf(testPost(sourcePostId = "page")), sources.associateWith { null },
            sources.map { SourceRunStatus(it, SourceRunState.SUCCESS) })
    }
    override suspend fun recordAccepted(result: ForYouFeedResult) = Unit
    override suspend fun resolvePost(postId: PostId): Post? = null
    override suspend fun updateBlacklist(profileId: String, entries: List<ForYouBlacklistEntry>, hidden: Boolean): ForYouBlacklistChange {
        lastBlacklistEntries = entries
        settings = settings.copy(forYouBlacklistByProfile = mapOf(profileId to if (hidden) entries else emptyList()))
        onSettings(settings)
        return ForYouBlacklistChange(settings, entries)
    }
}

private fun section(tag: String) = ForYouSection("PIXIV:$tag", tag, feed(tag))

private fun feed(tag: String, source: SourceKey = SourceKey.PIXIV, sort: SortMode = SortMode.NEWEST): ForYouFeedResult {
    val seed = mapOf(source to listOf(tag))
    val page = UnifiedSearchResult(listOf(testPost(source = source, sourcePostId = tag)), mapOf(source to "next"),
        listOf(SourceRunStatus(source, SourceRunState.SUCCESS)))
    val continuation = ForYouContinuation(forYouBaseQuery(sort), mapOf(source to forYouSourceQuery(source, listOf(tag), sort)),
        mapOf(source to 1.0), page.nextPageTokens)
    return ForYouFeedResult(seed, buildForYouSeedId(seed), 3, page, continuation)
}
