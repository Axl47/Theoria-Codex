package com.theoriacodex.app.settings.sites

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.theoriacodex.app.di.TheoriaAppContainer
import com.theoriacodex.app.settings.SettingsSitesEntry
import com.theoriacodex.app.ui.undo.LocalUndoSlot
import kotlinx.coroutines.CoroutineScope

/**
 * Builds the Settings home row and page for Sites from the Settings route's [CustomSitesViewModel]. A
 * removed site is removed in [removalScope], the shell's, so leaving Settings still completes it.
 */
@Composable
internal fun rememberCustomSitesEntry(container: TheoriaAppContainer, removalScope: CoroutineScope): SettingsSitesEntry {
    val undo = LocalUndoSlot.current
    val owner = viewModel(key = "custom-sites") { CustomSitesViewModel(container.customSites, undo = undo, removalScope = removalScope) }
    val state by owner.state.collectAsStateWithLifecycle()
    return SettingsSitesEntry(summary = customSitesSummary(state.shownSites)) { onBack, accounts ->
        CustomSitesScreen(state, accounts, owner::onAction, onBack)
    }
}
