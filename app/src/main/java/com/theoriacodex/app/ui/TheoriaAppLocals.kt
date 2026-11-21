package com.theoriacodex.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theoriacodex.app.codex.CodexCollectionSource
import com.theoriacodex.app.codex.CodexSaveRelevanceLoader
import com.theoriacodex.app.codex.LocalCodexPostIds
import com.theoriacodex.app.codex.LocalCodexSaveRelevanceLoader
import com.theoriacodex.app.codex.LocalQuickSave
import com.theoriacodex.app.lift.rememberLiftSession
import com.theoriacodex.app.recommend.LikedTagCounts
import com.theoriacodex.app.recommend.LocalLikedTagCounts
import com.theoriacodex.app.recommend.observeLikedTagCounts
import com.theoriacodex.app.related.LocalRelatedPostsSupport
import com.theoriacodex.app.search.LocalPostWatchPresentation
import com.theoriacodex.app.search.rememberPostWatchPresentation
import com.theoriacodex.app.source.LocalSourceHealth
import com.theoriacodex.app.ui.components.LocalPostMenuActions
import com.theoriacodex.app.ui.navigation.LocalLiftController
import com.theoriacodex.app.ui.navigation.LocalNewCodexOpener
import com.theoriacodex.app.ui.navigation.LocalTrailOpener
import com.theoriacodex.app.ui.navigation.LocalTriageOpener
import com.theoriacodex.app.ui.trails.rememberTrailOpener
import com.theoriacodex.app.ui.routes.browsingMetadataOwner
import com.theoriacodex.app.viewer.LocalViewerReturnTransition
import com.theoriacodex.app.viewer.ViewerReturnTransition
import com.theoriacodex.domain.model.PostId
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Every app-wide CompositionLocal the shell provides to its overlays and destinations. */
@Composable
internal fun ShellCompositionLocals(shell: TheoriaShell, content: @Composable () -> Unit) {
    val saveRelevanceLoader = rememberCodexSaveRelevanceLoader(shell)
    val watchPresentation = rememberPostWatchPresentation(shell.data.readingPositions, shell.data.recentsRepository)
    val viewerReturnTransition = remember { ViewerReturnTransition() }
    val relatedPostsSupport = remember(shell) { shell.features.relatedPosts::supports }
    CompositionLocalProvider(
        LocalTrailOpener provides rememberTrailOpener(shell),
        LocalCodexPostIds provides rememberCodexPostIds(shell),
        LocalPostWatchPresentation provides watchPresentation,
        LocalCodexSaveRelevanceLoader provides saveRelevanceLoader,
        LocalQuickSave provides rememberQuickSave(shell, saveRelevanceLoader),
        LocalLikedTagCounts provides rememberLikedTagCounts(shell),
        LocalViewerReturnTransition provides viewerReturnTransition,
        LocalPostMenuActions provides rememberPostMenuActions(shell),
        LocalLiftController provides rememberLiftSession(),
        LocalNetworkOnline provides shell.sources.networkOnline,
        LocalSourceHealth provides rememberSourceHealth(shell),
        LocalRelatedPostsSupport provides relatedPostsSupport,
        LocalTriageOpener provides rememberTriageOpener(shell),
        LocalNewCodexOpener provides remember { NewCodexRequests() },
        content = content,
    )
}

/** Ranks Codices for "Save to Codex" from the saved library. */
@Composable
private fun rememberCodexSaveRelevanceLoader(shell: TheoriaShell): CodexSaveRelevanceLoader = remember(shell) {
    val source = CodexCollectionSource(shell.data.codexRepository, shell.data.storageDirectory, shell.data.cacheRepository)
    CodexSaveRelevanceLoader(shell.data.codexRepository, shell.scope, observeLibrary = source::observeSavedLibrary)
}

/** The active profile's tag counts across its Likes, kept from the first collector on for the app session. */
@Composable
private fun rememberLikedTagCounts(shell: TheoriaShell): StateFlow<LikedTagCounts> = remember(shell) {
    observeLikedTagCounts(shell.data.settingsRepository, shell.data.likesRepository)
        .stateIn(shell.scope, SharingStarted.Lazily, LikedTagCounts.Empty)
}

/** The app-wide in-a-Codex set behind [LocalCodexPostIds], read from the shared browsing metadata. */
@Composable
private fun rememberCodexPostIds(shell: TheoriaShell): State<Set<PostId>> {
    val metadata = browsingMetadataOwner(shell.data)
    return remember(metadata) { metadata.state.map { it?.codexPostIds.orEmpty() }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(emptySet())
}
