package com.theoriacodex.app.recommend

import com.theoriacodex.app.recommend.state.ForYouAction
import com.theoriacodex.app.recommend.state.ForYouEffect
import com.theoriacodex.app.recommend.state.ForYouUiState
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.SourceRuntimeSettings
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.data.repository.ForYouBlacklistEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext

/** Overview previews live with the route owner, independently of the selected feed's paging. */
data class ForYouHomeState(
    val visible: Boolean = false,
    val sections: List<ForYouSection> = emptyList(),
    val mixedFeed: ForYouFeedResult? = null,
    val activeSection: ForYouSection? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val pendingSectionIds: Set<String> = emptySet(),
)

internal class ForYouHomeController(
    private val scope: CoroutineScope,
    private val engine: ForYouRouteEngine,
    private val state: () -> ForYouUiState,
    private val update: (ForYouHomeState) -> Unit,
    private val request: () -> ForYouFeedRequest?,
    private val admit: (ForYouFeedResult) -> Unit,
    private val emit: (ForYouEffect) -> Unit,
    private val settingsChanged: (AppSettings) -> Unit,
) {
    private var context: ForYouHomeContext? = null
    private var generation = 0L
    private var job: Job? = null

    fun synchronize() {
        val next = request()?.let { ForYouHomeContext(it) } ?: return
        if (next == context) return
        val previous = context
        context = next
        val scopeChanged = previous?.profileId != next.profileId || previous.source != next.source ||
            previous.sources != next.sources || previous.runtime != next.runtime || previous.blacklist != next.blacklist
        val home = state().home
        update(home.copy(sections = emptyList(), pendingSectionIds = emptySet(),
            mixedFeed = if (scopeChanged || previous.sort != next.sort) null else home.mixedFeed,
            activeSection = if (scopeChanged) null else home.activeSection))
        load(shuffle = false)
    }

    fun handle(action: ForYouAction): Boolean {
        if (action == ForYouAction.ShowHome || action == ForYouAction.OpenMixedFeed || action is ForYouAction.OpenSection) {
            retainCurrentFeed()
        }
        when (action) {
            ForYouAction.ShowHome -> update(state().home.copy(visible = true))
            ForYouAction.OpenMixedFeed -> state().home.mixedFeed?.let { feed ->
                update(state().home.copy(visible = false, activeSection = null))
                admit(feed)
            }
            is ForYouAction.OpenSection -> state().home.sections.firstOrNull { it.id == action.id }?.let { section ->
                update(state().home.copy(visible = false, activeSection = section))
                admit(section.feed)
            }
            ForYouAction.RefreshSections -> if (state().home.pendingSectionIds.isEmpty()) load(shuffle = true)
            is ForYouAction.PinSection -> pin(action.id)
            is ForYouAction.DismissSection -> dismiss(action.id)
            else -> return false
        }
        return true
    }

    private fun pin(id: String) = mutate(id) { section, isCurrent ->
        val savedId = engine.pinSection(section, !section.pinned)
        if (!isCurrent()) return@mutate
        val home = state().home
        update(home.copy(sections = home.sections.map {
            if (it.id == section.id) it.copy(savedSearchId = savedId) else it
        }, activeSection = home.activeSection?.let {
            if (it.id == section.id) it.copy(savedSearchId = savedId) else it
        }))
        emit(ForYouEffect.ShowMessage(if (savedId == null) "Section unpinned from Saved Searches" else "Section pinned in Saved Searches"))
    }

    private fun dismiss(id: String) = mutate(id) { section, isCurrent ->
        if (section.pinned) return@mutate
        val profile = state().activeProfileId
        val entries = section.feed.seed.map { (source, tags) -> ForYouBlacklistEntry(source, tags) }
        val changed = engine.updateBlacklist(profile, entries, true)
        if (!isCurrent()) return@mutate
        update(state().home.copy(sections = state().home.sections.filterNot { it.id == section.id }))
        if (changed.entries.isNotEmpty()) emit(ForYouEffect.SeedHidden(profile, changed.entries))
        settingsChanged(changed.settings)
    }

    private fun retainCurrentFeed() {
        val current = state()
        if (current.isRefreshing) return
        val selected = current.home.activeSection
        val original = selected?.feed ?: current.home.mixedFeed ?: return
        if (original.seedId != current.seedId) return
        val retained = original.copy(page = original.page.copy(items = current.results, statuses = current.statuses),
            continuation = current.continuation)
        update(if (selected == null) current.home.copy(mixedFeed = retained) else current.home.copy(
            activeSection = selected.copy(feed = retained),
            sections = current.home.sections.map { if (it.id == selected.id) it.copy(feed = retained) else it },
        ))
    }

    private fun load(shuffle: Boolean) {
        val input = request()?.copy(shuffle = shuffle) ?: return
        val loadMixed = state().home.activeSection != null && state().home.mixedFeed == null
        val expected = ++generation
        job?.cancel()
        update(state().home.copy(loading = true, error = null, pendingSectionIds = emptySet()))
        job = scope.launch {
            try {
                val sections = engine.loadSections(input)
                val mixed = if (loadMixed) engine.execute(input) else null
                currentCoroutineContext().ensureActive()
                if (generation == expected) update(state().home.copy(sections = sections, loading = false,
                    mixedFeed = mixed ?: state().home.mixedFeed))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (generation == expected) update(state().home.copy(loading = false,
                    error = error.message ?: "Could not load interests"))
            }
        }
    }

    private fun mutate(id: String, operation: suspend (ForYouSection, () -> Boolean) -> Unit) {
        val section = state().home.sections.firstOrNull { it.id == id } ?: return
        if (id in state().home.pendingSectionIds) return
        val expected = generation
        update(state().home.copy(pendingSectionIds = state().home.pendingSectionIds + id))
        scope.launch {
            try {
                operation(section) { expected == generation }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (expected == generation) emit(ForYouEffect.ShowMessage(error.message ?: "Could not update section"))
            } finally {
                if (expected == generation) update(state().home.copy(pendingSectionIds = state().home.pendingSectionIds - id))
            }
        }
    }
}

private data class ForYouHomeContext(
    val profileId: String,
    val source: SourceKey?,
    val sources: List<SourceKey>,
    val sort: SortMode,
    val runtime: SourceRuntimeSettings,
    val blacklist: List<ForYouBlacklistEntry>,
) {
    constructor(request: ForYouFeedRequest) : this(
        request.settings.activeProfileId, request.selectedSource, request.availableSources,
        request.sortMode, request.settings.runtime,
        request.settings.forYouBlacklistByProfile[request.settings.activeProfileId].orEmpty(),
    )
}
