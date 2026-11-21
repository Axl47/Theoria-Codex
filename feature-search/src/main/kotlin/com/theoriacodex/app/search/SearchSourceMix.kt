package com.theoriacodex.app.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.theoriacodex.app.search.omnibox.OmniboxEntry
import com.theoriacodex.app.search.omnibox.OmniboxKind
import com.theoriacodex.app.search.state.SearchAction
import com.theoriacodex.app.search.state.SearchSourceScope
import com.theoriacodex.app.search.state.SearchUiState
import com.theoriacodex.app.settings.UnifiedSourceSettings
import com.theoriacodex.app.ui.components.ActionMenuSheet
import com.theoriacodex.data.repository.SettingDestinations
import com.theoriacodex.data.repository.SourceRuntimeSettings
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SourceKey

/**
 * The Unified source mix as Search's source picker edits it: the weights stored in [runtime], each change written
 * through [onWeightsChanged] for the sources it covers. The settings write comes back through
 * [SearchViewModel.synchronizeEnvironment], which reruns the search on screen under the live-query pause.
 */
class SearchSourceMix(
    val runtime: SourceRuntimeSettings,
    val onWeightsChanged: (Set<SourceKey>, Map<SourceKey, Double>) -> Unit,
)

/** Whether the draft searches several sources at once (Unified or a held mix), the only searches a mix applies to. */
internal val SearchUiState.searchesUnified: Boolean get() = query.draftSourceScope !is SearchSourceScope.Single

/** The Source mix sheet over the draft's sources, while [chrome] has it open and the draft is Unified. */
@Composable
internal fun SearchSourceMixSheet(state: SearchUiState, chrome: SearchChromeState, sourceMix: SearchSourceMix?) {
    if (!chrome.showSourceMix) return
    if (sourceMix == null || !state.searchesUnified) {
        LaunchedEffect(Unit) { chrome.showSourceMix = false }
        return
    }
    val sources = state.query.draftSources
    ActionMenuSheet({ chrome.showSourceMix = false }) {
        UnifiedSourceSettings(sources, sourceMix.runtime) { weights -> sourceMix.onWeightsChanged(sources, weights) }
    }
}

/**
 * The composer's "Source mix" command, so typed text finds it as Settings once did: it switches a single-source
 * draft to Unified and opens the sheet. None without a [sourceMix] to edit.
 */
internal fun sourceMixCommands(
    state: SearchUiState,
    chrome: SearchChromeState,
    sourceMix: SearchSourceMix?,
    onAction: (SearchAction) -> Unit,
): List<OmniboxEntry> {
    if (sourceMix == null) return emptyList()
    val destination = SettingDestinations.SourceMix
    return listOf(
        OmniboxEntry.Action(OmniboxKind.COMMAND, destination.title, keywords = destination.keywords) {
            if (!state.searchesUnified) onAction(SearchAction.SelectMode(QueryMode.Unified))
            chrome.showSourceMix = true
        },
    )
}
