package com.theoriacodex.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.theoriacodex.app.codex.CodexListScreen
import com.theoriacodex.app.codex.likesCodexIdForProfile
import com.theoriacodex.app.library.BareSectionHeader
import com.theoriacodex.app.library.LibraryHeader
import com.theoriacodex.app.library.LibrarySection
import com.theoriacodex.app.library.LibrarySectionHeader
import com.theoriacodex.app.media.MediaDurationRouteViewModel
import com.theoriacodex.app.media.MediaDurationStates
import com.theoriacodex.app.recents.RecentsClearTarget
import com.theoriacodex.app.recents.RecentsEntryRemoval
import com.theoriacodex.app.recents.RecentsLoading
import com.theoriacodex.app.recents.RecentsRelatedViewModel
import com.theoriacodex.app.recents.RecentsScreen
import com.theoriacodex.app.recents.RecentsUndoAnchor
import com.theoriacodex.app.codex.CodexUndoAnchors
import com.theoriacodex.app.ui.undo.LocalUndoSlot
import com.theoriacodex.app.ui.undo.UndoAnchor
import com.theoriacodex.app.ui.undo.UndoPill
import com.theoriacodex.app.ui.undo.UndoSlot
import com.theoriacodex.app.source.creatorBrowsingSources
import com.theoriacodex.app.ui.components.codexFeedFabContext
import com.theoriacodex.app.ui.components.feedDurations
import com.theoriacodex.app.ui.routes.CodexDestinationStateBoundary
import com.theoriacodex.app.ui.routes.RecentsDestinationState
import com.theoriacodex.app.ui.routes.RecentsDestinationStateBoundary
import com.theoriacodex.app.ui.routes.SavedCodexRoute
import com.theoriacodex.data.repository.CodexSortMode
import com.theoriacodex.data.repository.RecentPostEntry
import com.theoriacodex.data.repository.RecentPostSection
import com.theoriacodex.data.repository.ViewerStreamSource
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/**
 * The Library tab: one header (title, the section's actions, the Settings gear) over Codices, Saved
 * or History. Only the chosen section is composed, so tab reselect scrolls whichever one shows.
 */
@Composable
internal fun LibraryDestination(shell: TheoriaShell) {
    val store = shell.data.librarySections
    val section by store.section.collectAsStateWithLifecycle()
    val marked = if (rememberSavedNewPosts(shell.data).isEmpty()) emptySet() else setOf(LibrarySection.Saved)
    val header: LibrarySectionHeader = { actions ->
        LibraryHeader(
            section, store::select, onOpenSettings = { shell.openSettings() }, marked = marked,
            transfers = { LibraryTransfersHeaderButton(shell.transfers) }, actions = actions,
        )
    }
    Box(Modifier.fillMaxSize()) {
        when (section) {
            LibrarySection.Codices -> CodexLibraryDestination(shell, actionCodexId = null, header = header)
            LibrarySection.Saved -> SavedLibraryDestination(shell, header)
            LibrarySection.History -> RecentsDestination(shell, header)
        }
        val undoAnchor = when (section) {
            LibrarySection.Codices -> CodexUndoAnchors.Library
            LibrarySection.Saved -> null
            LibrarySection.History -> RecentsUndoAnchor
        }
        undoAnchor?.let { UndoPill(it, Modifier.align(Alignment.BottomCenter)) }
    }
}

/** Saved's shelves (Likes, Followed, saved Searches) as a Library section. */
@Composable
private fun SavedLibraryDestination(shell: TheoriaShell, header: LibrarySectionHeader) {
    SavedCodexRoute(
        data = shell.data,
        sources = shell.sources,
        features = shell.features,
        restoreRegistry = shell.feedFabRestoreRegistry,
        onOpenCodex = { shell.navController.navigate(AppRoute.codexDetail(it)) },
        onOpenViewer = shell::openCodexViewer,
        onOpenCodexActions = shell.overlays::openCollectionActions,
        resolvePost = { shell.viewerRouteWorkflow.resolvePost(it, ViewerStreamSource.CODEX) },
        onToggleLike = { shell.toggleLikeAndSyncCodex(it) },
        header = { Box(Modifier.padding(horizontal = 16.dp).padding(top = 12.dp)) { header {} } },
    )
}

private fun TheoriaShell.clearRecents(target: RecentsClearTarget, state: RecentsDestinationState, undo: UndoSlot) {
    scope.launch {
        recentsClearWorkflow.clear(
            target = target,
            watchedPosts = state.watchedPosts,
            codexPosts = state.codexPosts,
            searches = state.searches,
            fypSearches = state.fypSearches,
            showActionableFeedback = { message, action -> undo.awaitAction(message, RecentsUndoAnchor, action) },
        )
    }
}

/** Resolves durations for every post Recents can show. */
@Composable
private fun rememberRecentsDurationOwner(shell: TheoriaShell, state: RecentsDestinationState): MediaDurationRouteViewModel {
    val durationOwner = viewModel<MediaDurationRouteViewModel>(
        key = "recents-duration-owner",
        factory = MediaDurationRouteViewModel.factory(shell.features.mediaDurationCoordinator, "recents"),
    )
    SideEffect {
        durationOwner.synchronize(
            identity = "recents",
            posts = (state.watchedPosts + state.codexPosts).map(RecentPostEntry::post),
            resolveInBackground = false,
        )
    }
    return durationOwner
}

@Composable
private fun RecentsDestination(shell: TheoriaShell, header: LibrarySectionHeader) {
    val undo = LocalUndoSlot.current
    val removal = remember(shell, undo) { RecentsEntryRemoval(shell.data.recentsRepository, undo, shell.scope) }
    val removedSearch by removal.removedSearch.collectAsStateWithLifecycle()
    RecentsDestinationStateBoundary(shell.data, loading = { RecentsLoading(header) }) { state ->
        val durationOwner = rememberRecentsDurationOwner(shell, state)
        val relatedOwner = viewModel<RecentsRelatedViewModel>(
            key = "recents-related-owner",
            factory = RecentsRelatedViewModel.factory(shell.features.relatedPosts),
        )
        RecentsScreen(
            related = rememberRelatedShelfBinding(shell, relatedOwner.state, relatedOwner.related),
            onShownPostsChanged = relatedOwner::showing,
            onSaveSearch = shell.owners.savedSearches::saveInPlace,
            onRemoveSearch = removal::removeSearch,
            onRemovePost = removal::removePost,
            removedSearch = removedSearch,
            watchedPosts = state.watchedPosts,
            codexPosts = state.codexPosts,
            searches = state.searches,
            fypSearches = state.fypSearches,
            activity = state.activity,
            pixivUgoiraClient = shell.sources.pixivUgoiraClient,
            // Recents filters no lengths, so it only reports what its cards learn.
            durations = durationOwner.feedDurations(MediaDurationStates.EMPTY, resolveInBackground = false),
            likedPostIds = state.likedPostIds,
            creatorBrowsingSources = shell.sources.registry.creatorBrowsingSources(),
            onToggleLike = { post ->
                shell.toggleLikeAndSyncCodex(post) { outcome -> relatedOwner.related.onLikeCommitted(post, outcome) }
            },
            onRequestSaveToCodex = { post -> shell.requestSaveToCodex(post) },
            onSaveToDevice = shell::requestSaveToDevice,
            onGoToSearch = { shell.navigation.showTab(TopLevelDestination.Search) },
            onOpenWatchedPost = { entries, index -> shell.openRecentSection(entries, index, RecentPostSection.WATCHED) },
            onOpenCodexPost = { entries, index -> shell.openRecentSection(entries, index, RecentPostSection.CODEX) },
            onStartOver = { post, section ->
                val entries = if (section == RecentPostSection.CODEX) state.codexPosts else state.watchedPosts
                val index = entries.indexOfFirst { it.post.id == post.id }
                if (index >= 0) shell.openRecentSection(entries, index, section, startOver = true)
            },
            onOpenSearch = shell::openHistoricalSearch,
            onOpenFypSearch = shell::openHistoricalForYouSearch,
            onClear = { target -> shell.clearRecents(target, state, undo) },
            header = header,
        )
    }
}

/**
 * The Codex library. Called from two places on purpose: Library's Codices section, and the shell's
 * keyed action overlay above the NavHost. Each call retains its flows under its own ViewModel store owner.
 */
@Composable
internal fun CodexLibraryDestination(
    shell: TheoriaShell,
    actionCodexId: String?,
    header: LibrarySectionHeader = BareSectionHeader,
) {
    val commands = shell.owners.codexCommands
    // The keyed overlay floats over whatever screen opened it, so its offers use the shell's own pill.
    val undoAnchor = if (actionCodexId == null) CodexUndoAnchors.Library else UndoAnchor.App
    CodexDestinationStateBoundary(
        data = shell.data,
        sources = shell.sources,
        thumbnailCacheGeneration = shell.thumbnailCacheGeneration,
        savedSortFor = { codexId ->
            shell.feedFabRestoreRegistry.state(codexFeedFabContext(codexId))?.sortMode
                ?.let { saved -> CodexSortMode.entries.firstOrNull { it.name == saved } }
                ?: CodexSortMode.NEWEST_SAVED
        },
    ) { state ->
        CodexListScreen(
            codices = state.visibleCodices,
            itemCounts = state.itemCounts,
            codexCoverCandidates = state.coverCandidates,
            observeActionOptions = state.observeActionOptions,
            onOpenCodex = { codexId -> shell.navController.navigate(AppRoute.codexDetail(codexId)) },
            onImportCodex = { shell.codexImportLauncher.launch(arrayOf("application/json", "text/json", "text/plain")) },
            onDownloadCodex = { codexId ->
                shell.scope.launch(start = CoroutineStart.UNDISPATCHED) { shell.downloadCodex(codexId) }
            },
            onMakeAvailableOffline = shell.transfers.offline::makeAvailableOffline,
            onShareCodex = { codexId ->
                shell.scope.launch(start = CoroutineStart.UNDISPATCHED) { shell.shareCodex(codexId) }
            },
            onSearchFromCodex = { _, source, query -> shell.searchFromCodex(source, query) },
            onCleanCodex = { targetId, comparisonIds ->
                commands.clean(state.activeProfile.profileId, targetId, comparisonIds, undoAnchor)
            },
            onCommitReorder = { ids -> commands.reorder(ids, undoAnchor) },
            onCreateCodex = { name -> commands.create(state.activeProfile.profileId, name, undoAnchor) },
            onRenameCodex = { id, name -> commands.rename(id, name, undoAnchor) },
            onSetAutomaticTag = { id, tag, enabled -> commands.automaticTag(id, tag, enabled, undoAnchor) },
            onDeleteCodex = { id -> commands.delete(id, state.activeProfile.profileId, undoAnchor) },
            initialActionCodexId = actionCodexId,
            onActionsDismissed = { shell.overlays.codexActionTargetId = null },
            likesCodexId = likesCodexIdForProfile(state.activeProfile.profileId),
            librarySort = state.librarySort,
            libraryUsage = state.libraryUsage,
            observeCleaningMatches = state.observeCleaningMatches,
            onLibrarySortChange = { sort ->
                shell.scope.launch { shell.data.settingsRepository.updateSettings { it.copy(codexLibrarySort = sort) } }
            },
            header = header,
        )
    }
}
