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
    val addingSearch: Boolean = false,
    val mixedLoading: Boolean = false,
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
    private var appendJob: Job? = null
    private var blacklistUpdates = 0
    val isUpdatingBlacklist: Boolean get() = blacklistUpdates > 0
    private val dismissed = mutableMapOf<String, DismissedSection>()

    fun synchronize() {
        val next = request()?.let { ForYouHomeContext(it) } ?: return
        if (next == context) return
        val previous = context
        context = next
        if (previous != null && isUpdatingBlacklist && previous.copy(blacklist = next.blacklist) == next) return
        appendJob?.cancel()
        dismissed.clear()
        val scopeChanged = previous?.profileId != next.profileId || previous.source != next.source ||
            previous.sources != next.sources || previous.runtime != next.runtime || previous.blacklist != next.blacklist
        val home = state().home
        update(home.copy(sections = emptyList(), addingSearch = false, pendingSectionIds = emptySet(),
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
            is ForYouAction.DismissSection -> remove(action.id, save = false)
            is ForYouAction.SaveSection -> remove(action.id, save = true)
            ForYouAction.NewSection -> appendSearch()
            is ForYouAction.ReorderSections -> reorder(action.ids)
            is ForYouAction.UndoSeedBlacklist -> return restore(action)
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

    private fun remove(id: String, save: Boolean) = mutate(id) { section, isCurrent ->
        val profile = state().activeProfileId
        val index = state().home.sections.indexOfFirst { it.id == id }
        if (save && !section.pinned) engine.pinSection(section, true)
        blacklistUpdates++
        try {
            val entries = section.feed.seed.map { (source, tags) -> ForYouBlacklistEntry(source, tags) }
            val changed = engine.updateBlacklist(profile, entries, true)
            if (!isCurrent()) return@mutate
            val home = state().home
            update(home.copy(sections = home.sections.filterNot { it.id == id },
                activeSection = home.activeSection?.takeUnless { it.id == id }))
            if (save) {
                emit(ForYouEffect.ShowMessage("Search saved to Codex"))
            } else if (changed.entries.isNotEmpty()) {
                dismissed[id] = DismissedSection(profile, index, section, changed.entries)
                emit(ForYouEffect.SeedHidden(profile, changed.entries))
            }
            settingsChanged(changed.settings)
        } finally {
            blacklistUpdates--
        }
    }

    private fun restore(action: ForYouAction.UndoSeedBlacklist): Boolean {
        val removed = dismissed.values.firstOrNull { it.profile == action.profileId && it.entries == action.entries }
            ?: return false
        if (removed.profile != state().activeProfileId) return true
        mutateSection(removed.section) { _, isCurrent ->
            blacklistUpdates++
            try {
                val changed = engine.updateBlacklist(removed.profile, removed.entries, false)
                if (!isCurrent()) return@mutateSection
                val rows = state().home.sections.toMutableList()
                if (rows.none { it.id == removed.section.id }) rows.add(removed.index.coerceAtMost(rows.size), removed.section)
                update(state().home.copy(sections = rows))
                dismissed.remove(removed.section.id)
                settingsChanged(changed.settings)
            } finally {
                blacklistUpdates--
            }
        }
        return true
    }

    private fun reorder(ids: List<String>) {
        val rows = state().home.sections
        if (ids.size != rows.size || ids.toSet() != rows.map { it.id }.toSet()) return
        val byId = rows.associateBy { it.id }
        update(state().home.copy(sections = ids.map { byId.getValue(it) }))
    }

    private fun appendSearch() {
        val home = state().home
        if (home.loading || home.addingSearch || home.pendingSectionIds.isNotEmpty()) return
        val input = request()?.copy(shuffle = true, additionalSection = true,
            excludedSectionIds = home.sections.mapTo(mutableSetOf()) { it.id }) ?: return
        val expected = generation
        update(home.copy(addingSearch = true, error = null))
        appendJob = scope.launch {
            try {
                val section = engine.loadSections(input).firstOrNull()
                currentCoroutineContext().ensureActive()
                acceptAdditional(expected, section)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (generation == expected) update(state().home.copy(error = error.message ?: "Could not create search"))
            } finally {
                if (generation == expected) update(state().home.copy(addingSearch = false))
            }
        }
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
        if (state().home.addingSearch) return
        val loadMixed = shuffle || (state().home.activeSection != null && state().home.mixedFeed == null)
        val expected = ++generation
        job?.cancel()
        update(state().home.copy(loading = true, mixedLoading = loadMixed, error = null, pendingSectionIds = emptySet()))
        job = scope.launch {
            try {
                val sections = engine.loadSections(input)
                val mixed = if (loadMixed) engine.execute(input) else null
                currentCoroutineContext().ensureActive()
                acceptLoaded(expected, sections, mixed)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (generation == expected) update(state().home.copy(loading = false, mixedLoading = false,
                    error = error.message ?: "Could not load interests"))
            }
        }
    }

    private fun acceptAdditional(expected: Long, section: ForYouSection?) {
        if (generation != expected) return
        if (section == null) {
            emit(ForYouEffect.ShowMessage("No new searches yet. Like more posts to discover more interests."))
        } else if (state().home.sections.none { it.id == section.id }) {
            update(state().home.copy(sections = state().home.sections + section))
        }
    }

    private fun acceptLoaded(expected: Long, sections: List<ForYouSection>, mixed: ForYouFeedResult?) {
        if (generation != expected) return
        val home = state().home
        val order = home.sections.map { it.id }
        val ordered = sections.sortedBy { order.indexOf(it.id).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }
        update(home.copy(sections = ordered, loading = false, mixedLoading = false,
            mixedFeed = mixed ?: home.mixedFeed))
        if (mixed != null && home.activeSection == null) admit(mixed)
    }

    private fun mutate(id: String, operation: suspend (ForYouSection, () -> Boolean) -> Unit) {
        val section = state().home.sections.firstOrNull { it.id == id } ?: return
        mutateSection(section, operation)
    }

    private fun mutateSection(section: ForYouSection, operation: suspend (ForYouSection, () -> Boolean) -> Unit) {
        val id = section.id
        if (id in state().home.pendingSectionIds || state().home.loading) return
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

private data class DismissedSection(
    val profile: String,
    val index: Int,
    val section: ForYouSection,
    val entries: List<ForYouBlacklistEntry>,
)
