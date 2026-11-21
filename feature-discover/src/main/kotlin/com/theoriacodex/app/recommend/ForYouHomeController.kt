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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
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
    private val dispatch: (ForYouAction) -> Unit,
) {
    private var context: ForYouHomeContext? = null
    private var generation = 0L
    private var job: Job? = null
    private var appendJob: Job? = null
    private var blacklistUpdates = 0
    val isUpdatingBlacklist: Boolean get() = blacklistUpdates > 0
    /** Recent hides, newest last, kept across reloads and profiles so a late Undo still finds its own. */
    private val hidden = mutableListOf<HiddenShelves>()
    /** Tag hides and unhides (Undo included) still being written; closing a why panel waits for them. */
    private val tagWrites = MutableStateFlow(0)

    fun synchronize() {
        if (!state().home.visible) return
        val next = request()?.let { ForYouHomeContext(it) } ?: return
        if (next == context) return
        val previous = context
        context = next
        if (previous != null && isUpdatingBlacklist && previous.copy(blacklist = next.blacklist) == next) return
        appendJob?.cancel()
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
            is ForYouAction.UnhideTag -> unhideTag(action.source, action.tag)
            is ForYouAction.CloseWhy -> closeWhy(action.target)
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

    /** Strikes a tag from a why panel, then refills the slots of the shelves it took. */
    private fun hideTag(entry: ForYouBlacklistEntry) {
        val expected = generation
        val isCurrent = { expected == generation }
        tagWrites.update { it + 1 }
        launchGuarded(expected) {
            val record = try {
                hide(state().activeProfileId, listOf(entry), isCurrent, hiddenLabel(listOf(entry)), ForYouWhyUndoAnchor)
            } finally {
                tagWrites.update { it - 1 }
            }
            record?.let { refill(it, isCurrent) }
        }
    }

    /** Unstrikes a tag in a why panel: a recent strike of it is undone in place, an older one is simply removed. */
    private fun unhideTag(source: SourceKey, tag: String) {
        val profile = state().activeProfileId
        val entries = state().blacklistEntries.filter { listOf(it).hidesSeed(source, listOf(tag)) }
        if (entries.isEmpty()) return
        hidden.lastOrNull { it.profile == profile && it.entries == entries && it.saved == null }?.let { return restore(it) }
        tagWrites.update { it + 1 }
        launchGuarded(generation, onDone = { tagWrites.update { it - 1 } }) {
            settingsChanged(engine.updateBlacklist(profile, entries, false).settings)
        }
    }

    /** Once the tags struck in a closed why panel are written, lets the drilled-in feed it explained catch up. */
    private fun closeWhy(target: ForYouWhyTarget) {
        scope.launch {
            tagWrites.first { it == 0 }
            val home = state().home
            if (home.activeSection?.id != target.sectionId) return@launch
            target.afterClose(home.visible, state().blacklistEntries)?.let(dispatch)
        }
    }

    /**
     * Fills up to [MAX_REFILLS] of the slots [record] freed with new shelves from the planner, each where a hidden
     * shelf stood. An Undo before a refill lands keeps it out, and so does a strike made while it loaded.
     */
    private suspend fun refill(record: HiddenShelves, isCurrent: () -> Boolean) {
        for ((index, _) in record.removed.take(MAX_REFILLS)) {
            val input = additionalRequest() ?: return
            val section = engine.loadSections(input).firstOrNull() ?: return
            if (!isCurrent() || hidden.none { it === record }) return
            val rows = state().home.sections
            if (rows.any { it.id == section.id } || hiddenNow(section)) continue
            update(state().home.copy(sections = rows.toMutableList().apply { add(index.coerceAtMost(size), section) }))
            record.refills += section.id
        }
    }

    /**
     * Hides [entries] for [profile], drops every shelf the profile's new hidden list hides (the subset rule can
     * take several; see [hidesShelf] for pinned ones), and offers Undo at [anchor]. [saved] is a search saved on
     * the way, which Undo removes. Returns what Undo restores, or null when nothing changed.
     */
    private suspend fun hide(
        profile: String,
        entries: List<ForYouBlacklistEntry>,
        isCurrent: () -> Boolean,
        label: String,
        anchor: UndoAnchor,
        saved: ForYouSection? = null,
    ): HiddenShelves? = writingBlacklist {
        val changed = engine.updateBlacklist(profile, entries, true)
        if (!isCurrent()) return@writingBlacklist null
        val blocked = changed.settings.forYouBlacklistByProfile[profile].orEmpty()
        val home = state().home
        val removed = home.sections.withIndex().filter { (_, section) -> blocked.hidesShelf(section.feed.seed, section.pinned) }
        val removedIds = removed.mapTo(mutableSetOf()) { it.value.id }
        // The drilled-in feed keeps its interest until its why panel closes; see ForYouWhyTarget.afterClose.
        update(home.copy(sections = home.sections.filterNot { it.id in removedIds },
            activeSection = home.activeSection?.takeUnless { home.visible && it.id in removedIds }))
        val record = if (changed.entries.isNotEmpty() || saved != null) {
            HiddenShelves(profile, changed.entries, removed, saved, generation).also(::keep)
        } else null
        record?.let { emit(ForYouEffect.SeedHidden(profile, changed.entries, label, anchor)) }
        settingsChanged(changed.settings)
        record
    }

    /** Keeps [record] for its Undo; only the newest few can still be offered, since one offer shows at a time. */
    private fun keep(record: HiddenShelves) {
        hidden += record
        if (hidden.size > MAX_HIDE_RECORDS) hidden.removeAt(0)
    }

    private fun restore(action: ForYouAction.UndoSeedBlacklist): Boolean {
        val record = hidden.lastOrNull { it.profile == action.profileId && it.entries == action.entries } ?: return false
        restore(record)
        return true
    }

    /**
     * Unhides [record]'s entries for the profile they were hidden for. In the same view its shelves return to their
     * places and its refills leave; after a reload (profile, source or reshuffle) the old places mean nothing, so
     * the settings change reloads the shelves instead.
     */
    private fun restore(record: HiddenShelves) {
        hidden.remove(record)
        val expected = generation
        tagWrites.update { it + 1 }
        val done = { tagWrites.update { it - 1 } }
        if (record.generation != expected || record.profile != state().activeProfileId) {
            launchGuarded(expected, done) { settingsChanged(unhide(record)) }
            return
        }
        launchGuarded(expected, done) {
            writingBlacklist {
                val settings = unhide(record)
                if (expected != generation) return@writingBlacklist
                val blocked = settings.forYouBlacklistByProfile[record.profile].orEmpty()
                val rows = state().home.sections.filterNot { it.id in record.refills }.toMutableList()
                record.removed.filterNot { (_, section) ->
                    blocked.hidesShelf(section.feed.seed, section.pinned) || rows.any { it.id == section.id }
                }.forEach { (index, section) -> rows.add(index.coerceAtMost(rows.size), section) }
                update(state().home.copy(sections = rows))
                settingsChanged(settings)
            }
        }
    }

    private suspend fun unhide(record: HiddenShelves): AppSettings {
        val changed = engine.updateBlacklist(record.profile, record.entries, false)
        record.saved?.let { engine.pinSection(it, false) }
        return changed.settings
    }

    /** Marks a blacklist write in flight, so the settings it produces do not reload the shelves it already updated. */
    private suspend fun <T> writingBlacklist(operation: suspend () -> T): T {
        blacklistUpdates++
        return try {
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
        val input = additionalRequest() ?: return
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

    /** The planner's request for one more shelf than those on screen; New search and refills both use it. */
    private fun additionalRequest(): ForYouFeedRequest? = request()?.copy(shuffle = true, additionalSection = true,
        excludedSectionIds = state().home.sections.mapTo(mutableSetOf()) { it.id })

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
        } else if (state().home.sections.none { it.id == section.id } && !hiddenNow(section)) {
            update(state().home.copy(sections = state().home.sections + section))
        }
    }

    private fun acceptLoaded(expected: Long, sections: List<ForYouSection>, mixed: ForYouFeedResult?) {
        if (generation != expected) return
        val home = state().home
        val order = home.sections.map { it.id }
        // A tag struck while these loaded must not bring back the shelves it hides.
        val ordered = sections.filterNot(::hiddenNow).sortedBy { order.indexOf(it.id).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }
        update(home.copy(sections = ordered, loading = false, mixedLoading = false,
            mixedFeed = mixed ?: home.mixedFeed))
        if (mixed != null && home.activeSection == null) admit(mixed)
    }

    /** Whether the profile's current hidden tags hide [section]; a shelf planned before a strike can land after it. */
    private fun hiddenNow(section: ForYouSection) = state().blacklistEntries.hidesShelf(section.feed.seed, section.pinned)

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

/**
 * What one hide removed, so its Undo can put it back: the shelves at their old positions, a search saved on the
 * way, and the [refills] that took their slots. [generation] is the load it happened in.
 */
private class HiddenShelves(
    val profile: String,
    val entries: List<ForYouBlacklistEntry>,
    val removed: List<IndexedValue<ForYouSection>>,
    val saved: ForYouSection?,
    val generation: Long,
) {
    val refills = mutableSetOf<String>()
}

/** Shelves refilled per hidden tag, so one strike cannot reshape the whole page. */
private const val MAX_REFILLS = 2

private const val MAX_HIDE_RECORDS = 4
