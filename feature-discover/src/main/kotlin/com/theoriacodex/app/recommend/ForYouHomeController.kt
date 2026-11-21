package com.theoriacodex.app.recommend

import com.theoriacodex.app.recommend.state.ForYouAction
import com.theoriacodex.app.recommend.state.ForYouEffect
import com.theoriacodex.app.recommend.state.ForYouUiState
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.SourceRuntimeSettings
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.data.repository.ForYouBlacklistEntry
import com.theoriacodex.app.ui.undo.UndoAnchor
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
    private val hidden = mutableListOf<HiddenShelves>()

    fun synchronize() {
        if (!state().home.visible) return
        val next = request()?.let { ForYouHomeContext(it) } ?: return
        if (next == context) return
        val previous = context
        context = next
        if (previous != null && isUpdatingBlacklist && previous.copy(blacklist = next.blacklist) == next) return
        appendJob?.cancel()
        hidden.clear()
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
            ForYouAction.ShowHome -> {
                update(state().home.copy(visible = true))
                synchronize()
            }
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
            is ForYouAction.HideTag -> hideTag(ForYouBlacklistEntry(action.source, listOf(action.tag)))
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
    }

    /** Dismiss hides the shelf's tags; Move to Saved also saves it first, and its Undo unsaves it again. */
    private fun remove(id: String, save: Boolean) = mutate(id) { section, isCurrent ->
        val savedId = if (save && !section.pinned) engine.pinSection(section, true) else null
        val entries = section.feed.seed.map { (source, tags) -> ForYouBlacklistEntry(source, tags) }
        val label = if (save) "Moved to Saved" else "Hid ${section.title}"
        hide(state().activeProfileId, entries, isCurrent, label, ForYouUndoAnchor, savedId?.let { section.copy(savedSearchId = it) })
    }

    private fun hideTag(entry: ForYouBlacklistEntry) {
        val expected = generation
        launchGuarded(expected) {
            hide(state().activeProfileId, listOf(entry), { expected == generation }, hiddenLabel(listOf(entry)), ForYouWhyUndoAnchor)
        }
    }

    /**
     * Hides [entries] for [profile], drops every shelf the profile's new hidden list hides (the subset rule can
     * take several), and offers Undo at [anchor]. [saved] is a search saved on the way, which Undo removes.
     */
    private suspend fun hide(
        profile: String,
        entries: List<ForYouBlacklistEntry>,
        isCurrent: () -> Boolean,
        label: String,
        anchor: UndoAnchor,
        saved: ForYouSection? = null,
    ) = writingBlacklist {
        val changed = engine.updateBlacklist(profile, entries, true)
        if (!isCurrent()) return@writingBlacklist
        val blocked = changed.settings.forYouBlacklistByProfile[profile].orEmpty()
        val home = state().home
        val removed = home.sections.withIndex().filter { (_, section) -> blocked.hidesAny(section.feed.seed) }
        val removedIds = removed.mapTo(mutableSetOf()) { it.value.id }
        // The drilled-in feed keeps its interest until its why panel closes; see ForYouWhyTarget.afterClose.
        update(home.copy(sections = home.sections.filterNot { it.id in removedIds },
            activeSection = home.activeSection?.takeUnless { home.visible && it.id in removedIds }))
        if (changed.entries.isNotEmpty() || saved != null) {
            hidden += HiddenShelves(profile, changed.entries, removed, saved)
            emit(ForYouEffect.SeedHidden(profile, changed.entries, label, anchor))
        }
        settingsChanged(changed.settings)
    }

    private fun restore(action: ForYouAction.UndoSeedBlacklist): Boolean {
        val record = hidden.lastOrNull { it.profile == action.profileId && it.entries == action.entries } ?: return false
        hidden.remove(record)
        if (record.profile != state().activeProfileId) return true
        val expected = generation
        launchGuarded(expected) {
            writingBlacklist {
                val changed = engine.updateBlacklist(record.profile, record.entries, false)
                record.saved?.let { engine.pinSection(it, false) }
                if (expected != generation) return@writingBlacklist
                val blocked = changed.settings.forYouBlacklistByProfile[record.profile].orEmpty()
                val rows = state().home.sections.toMutableList()
                record.removed.filterNot { (_, section) -> blocked.hidesAny(section.feed.seed) || rows.any { it.id == section.id } }
                    .forEach { (index, section) -> rows.add(index.coerceAtMost(rows.size), section) }
                update(state().home.copy(sections = rows))
                settingsChanged(changed.settings)
            }
        }
        return true
    }

    /** Marks a blacklist write in flight, so the settings it produces do not reload the shelves it already updated. */
    private suspend fun writingBlacklist(operation: suspend () -> Unit) {
        blacklistUpdates++
        try {
            operation()
        } finally {
            blacklistUpdates--
        }
    }

    /** Runs a shelf change started in [expected] generation; a failure in the same generation shows its message. */
    private fun launchGuarded(expected: Long, onDone: () -> Unit = {}, operation: suspend () -> Unit) {
        scope.launch {
            try {
                operation()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (expected == generation) emit(ForYouEffect.ShowMessage(error.message ?: "Could not update section"))
            } finally {
                onDone()
            }
        }
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
        // A tag struck while these loaded must not bring back the shelves it hides.
        val blocked = state().blacklistEntries
        val ordered = sections.filterNot { blocked.hidesAny(it.feed.seed) }.sortedBy { order.indexOf(it.id).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }
        update(home.copy(sections = ordered, loading = false, mixedLoading = false,
            mixedFeed = mixed ?: home.mixedFeed))
        if (mixed != null && home.activeSection == null) admit(mixed)
    }

    private fun mutate(id: String, operation: suspend (ForYouSection, () -> Boolean) -> Unit) {
        val section = state().home.sections.firstOrNull { it.id == id } ?: return
        if (id in state().home.pendingSectionIds || state().home.loading) return
        val expected = generation
        update(state().home.copy(pendingSectionIds = state().home.pendingSectionIds + id))
        launchGuarded(expected, onDone = {
            if (expected == generation) update(state().home.copy(pendingSectionIds = state().home.pendingSectionIds - id))
        }) { operation(section) { expected == generation } }
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

/** What one hide removed, so its Undo can put it back: the shelves at their old positions, and a search saved on the way. */
private data class HiddenShelves(
    val profile: String,
    val entries: List<ForYouBlacklistEntry>,
    val removed: List<IndexedValue<ForYouSection>>,
    val saved: ForYouSection?,
)
