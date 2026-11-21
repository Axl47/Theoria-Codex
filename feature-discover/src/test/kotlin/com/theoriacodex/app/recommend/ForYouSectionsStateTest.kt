package com.theoriacodex.app.recommend

import com.theoriacodex.app.feed.FeedCursor
import androidx.lifecycle.SavedStateHandle
import com.theoriacodex.app.recommend.state.ForYouAction
import com.theoriacodex.app.recommend.state.ForYouEffect
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

    @Test fun `hiding one tag refills at most two freed slots in place and undo swaps the originals back`() = runTest {
        val refills = ArrayDeque(listOf(section("city"), section("field"), section("meadow")))
        val engine = SectionsEngine().apply {
            sectionLoader = { request ->
                if (request.additionalSection) listOf(refills.removeFirst())
                else listOf(tagged("boats", "sea"), section("forest"), tagged("harbor", "boats"), section("boats"))
            }
        }
        val owner = ForYouViewModel(engine, SavedStateHandle(), coroutineScope = this)
        engine.onSettings = { owner.synchronizeEnvironment(it, 3) }
        owner.synchronizeEnvironment(AppSettings(), 3)
        advanceUntilIdle()
        val original = owner.state.value.home.sections
        val feedback = async { owner.effects.first { it is ForYouEffect.SeedHidden } as ForYouEffect.SeedHidden }
        owner.onAction(ForYouAction.HideTag(SourceKey.PIXIV, "boats"))
        advanceUntilIdle()
        assertEquals(listOf("city", "forest", "field"), owner.state.value.home.sections.map { it.title })
        assertEquals(1, refills.size)
        val hidden = feedback.await()
        assertEquals("Hid boats", hidden.label)
        assertEquals(ForYouWhyUndoAnchor, hidden.anchor)
        assertEquals(listOf(ForYouBlacklistEntry(SourceKey.PIXIV, listOf("boats"))), owner.state.value.blacklistEntries)
        owner.onAction(ForYouAction.UndoSeedBlacklist(hidden.profileId, hidden.entries))
        advanceUntilIdle()
        assertEquals(original, owner.state.value.home.sections)
        assertTrue(owner.state.value.blacklistEntries.isEmpty())
    }

    @Test fun `a refill planned before a second strike never brings back a shelf that strike hides`() = runTest {
        val planned = CompletableDeferred<Unit>()
        val engine = SectionsEngine().apply {
            sectionLoader = { request ->
                if (request.additionalSection) listOf(section("city")).also { planned.await() }
                else listOf(section("boats"), section("forest"))
            }
        }
        val owner = ForYouViewModel(engine, SavedStateHandle(), coroutineScope = this)
        engine.onSettings = { owner.synchronizeEnvironment(it, 3) }
        owner.synchronizeEnvironment(AppSettings(), 3)
        advanceUntilIdle()
        owner.onAction(ForYouAction.HideTag(SourceKey.PIXIV, "boats"))
        runCurrent()
        owner.onAction(ForYouAction.HideTag(SourceKey.PIXIV, "city"))
        runCurrent()
        planned.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("forest"), owner.state.value.home.sections.map { it.title })
    }

    @Test fun `a pinned shelf survives striking one of its tags`() = runTest {
        val pinned = tagged("boats", "sea").copy(title = "Harbors", savedSearchId = "saved")
        val engine = SectionsEngine().apply {
            sectionLoader = { request -> if (request.additionalSection) emptyList() else listOf(pinned, section("boats")) }
        }
        val owner = ForYouViewModel(engine, SavedStateHandle(), coroutineScope = this)
        owner.synchronizeEnvironment(AppSettings(), 3)
        advanceUntilIdle()
        owner.onAction(ForYouAction.HideTag(SourceKey.PIXIV, "boats"))
        advanceUntilIdle()
        assertEquals(listOf("Harbors"), owner.state.value.home.sections.map { it.title })
    }

    @Test fun `unhiding a just-struck tag in why puts its shelves back without a refill`() = runTest {
        val engine = SectionsEngine().apply {
            sectionLoader = { request -> if (request.additionalSection) emptyList() else listOf(tagged("boats", "sea"), section("forest")) }
        }
        val owner = ForYouViewModel(engine, SavedStateHandle(), coroutineScope = this)
        owner.synchronizeEnvironment(AppSettings(), 3)
        advanceUntilIdle()
        val original = owner.state.value.home.sections
        owner.onAction(ForYouAction.HideTag(SourceKey.PIXIV, "boats"))
        advanceUntilIdle()
        owner.onAction(ForYouAction.UnhideTag(SourceKey.PIXIV, "BOATS"))
        advanceUntilIdle()
        assertEquals(original, owner.state.value.home.sections)
        assertTrue(owner.state.value.blacklistEntries.isEmpty())
    }

    @Test fun `undo of a hide still unhides for its profile after a profile switch`() = runTest {
        val shelf = tagged("boats", "sea")
        val engine = SectionsEngine().apply {
            sectionLoader = { request -> if (request.additionalSection) emptyList() else listOf(shelf) }
        }
        val owner = ForYouViewModel(engine, SavedStateHandle(), coroutineScope = this)
        owner.synchronizeEnvironment(AppSettings(), 3)
        advanceUntilIdle()
        owner.onAction(ForYouAction.OpenSection(shelf.id))
        val feedback = async { owner.effects.first { it is ForYouEffect.SeedHidden } as ForYouEffect.SeedHidden }
        owner.onAction(ForYouAction.HideTag(SourceKey.PIXIV, "boats"))
        advanceUntilIdle()
        val hidden = feedback.await()
        owner.synchronizeEnvironment(AppSettings(activeProfileId = "other",
            recommendationProfiles = listOf(RecommendationProfile("other", "Other"))), 2)
        advanceUntilIdle()
        owner.onAction(ForYouAction.UndoSeedBlacklist(hidden.profileId, hidden.entries))
        advanceUntilIdle()
        assertEquals(Triple(AppSettings().activeProfileId, hidden.entries, false), engine.blacklistWrites.last())
    }

    @Test fun `closing why while a strike is still writing returns a drilled-in shelf to the overview once it lands`() = runTest {
        val shelf = tagged("boats", "sea")
        val engine = SectionsEngine().apply {
            sectionLoader = { request -> if (request.additionalSection) emptyList() else listOf(shelf, section("forest")) }
        }
        val owner = ForYouViewModel(engine, SavedStateHandle(), coroutineScope = this)
        owner.synchronizeEnvironment(AppSettings(), 3)
        advanceUntilIdle()
        owner.onAction(ForYouAction.OpenSection(shelf.id))
        val write = CompletableDeferred<Unit>()
        engine.blacklistGate = write
        owner.onAction(ForYouAction.HideTag(SourceKey.PIXIV, "boats"))
        runCurrent()
        owner.onAction(ForYouAction.CloseWhy(shelf.whyTarget()))
        runCurrent()
        assertFalse(owner.state.value.home.visible)
        write.complete(Unit)
        advanceUntilIdle()
        assertTrue(owner.state.value.home.visible)
        assertEquals(listOf("forest"), owner.state.value.home.sections.map { it.title })
    }

    @Test fun `undo after Move to Saved also removes the search it saved`() = runTest {
        val engine = SectionsEngine().apply { sectionLoader = { listOf(section("forest"), section("city")) } }
        val owner = ForYouViewModel(engine, SavedStateHandle(), coroutineScope = this)
        engine.onSettings = { owner.synchronizeEnvironment(it, 3) }
        owner.synchronizeEnvironment(AppSettings(), 3)
        advanceUntilIdle()
        val feedback = async { owner.effects.first { it is ForYouEffect.SeedHidden } as ForYouEffect.SeedHidden }
        owner.onAction(ForYouAction.SaveSection("PIXIV:forest"))
        advanceUntilIdle()
        val hidden = feedback.await()
        assertEquals("Moved to Saved", hidden.label)
        owner.onAction(ForYouAction.UndoSeedBlacklist(hidden.profileId, hidden.entries))
        advanceUntilIdle()
        assertEquals("saved-id", engine.unsavedSection?.savedSearchId)
        assertEquals(listOf("forest", "city"), owner.state.value.home.sections.map { it.title })
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
    val pages = mutableListOf<FeedCursor>()
    var sectionLoads = 0
    val sectionRequests = mutableListOf<ForYouFeedRequest>()
    var savedSection: ForYouSection? = null
    var unsavedSection: ForYouSection? = null
    var onSettings: (AppSettings) -> Unit = {}
    var lastBlacklistEntries: List<ForYouBlacklistEntry> = emptyList()
    val blacklistWrites = mutableListOf<Triple<String, List<ForYouBlacklistEntry>, Boolean>>()
    var blacklistGate: CompletableDeferred<Unit>? = null
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
        if (pinned) savedSection = section else unsavedSection = section
        return if (pinned) "saved-id" else null
    }
    override suspend fun execute(request: ForYouFeedRequest): ForYouFeedResult {
        requests += request
        val seed = request.historicalSeed ?: mapOf((request.selectedSource ?: SourceKey.PIXIV) to listOf("mixed"))
        return feed(seed.values.flatten().single(), seed.keys.single(), request.sortMode)
    }
    override suspend fun loadPage(continuation: FeedCursor, sources: Set<SourceKey>): UnifiedSearchResult {
        pages += continuation
        return UnifiedSearchResult(listOf(testPost(sourcePostId = "page")), sources.associateWith { null },
            sources.map { SourceRunStatus(it, SourceRunState.SUCCESS) })
    }
    override suspend fun recordAccepted(result: ForYouFeedResult) = Unit
    override suspend fun resolvePost(postId: PostId): Post? = null
    override suspend fun updateBlacklist(profileId: String, entries: List<ForYouBlacklistEntry>, hidden: Boolean): ForYouBlacklistChange {
        blacklistGate?.await()
        lastBlacklistEntries = entries
        blacklistWrites += Triple(profileId, entries, hidden)
        settings = settings.copy(forYouBlacklistByProfile = mapOf(profileId to if (hidden) entries else emptyList()))
        onSettings(settings)
        return ForYouBlacklistChange(settings, entries)
    }
}

private fun section(tag: String) = ForYouSection("PIXIV:$tag", tag, feed(tag))

private fun tagged(vararg tags: String) = tags.joinToString("+").let { id ->
    ForYouSection("PIXIV:$id", tags.joinToString(" + "), feed(id).copy(seed = mapOf(SourceKey.PIXIV to tags.toList())))
}

private fun feed(tag: String, source: SourceKey = SourceKey.PIXIV, sort: SortMode = SortMode.NEWEST): ForYouFeedResult {
    val seed = mapOf(source to listOf(tag))
    val page = UnifiedSearchResult(listOf(testPost(source = source, sourcePostId = tag)), mapOf(source to "next"),
        listOf(SourceRunStatus(source, SourceRunState.SUCCESS)))
    val continuation = FeedCursor(forYouBaseQuery(sort), mapOf(source to forYouSourceQuery(source, listOf(tag), sort)),
        mapOf(source to 1.0), page.nextPageTokens)
    return ForYouFeedResult(seed, buildForYouSeedId(seed), 3, page, continuation)
}
