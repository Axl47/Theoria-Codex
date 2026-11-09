package com.theoriacodex.app.settings.sites

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.theoriacodex.app.di.TheoriaAppContainer
import com.theoriacodex.app.settings.SettingsSitesEntry

/** Builds the Settings home row and page for Sites from the activity-retained [CustomSitesViewModel]. */
@Composable
internal fun rememberCustomSitesEntry(container: TheoriaAppContainer): SettingsSitesEntry {
    val owner = viewModel<CustomSitesViewModel>(key = "custom-sites", factory = CustomSitesViewModel.factory(container))
    val state by owner.state.collectAsStateWithLifecycle()
    return SettingsSitesEntry(summary = customSitesSummary(state.sites)) { onBack, accounts ->
        CustomSitesScreen(state, accounts, owner::onAction, onBack)
    }
}
