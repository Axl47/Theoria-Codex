package com.theoriacodex.app.settings.sites

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.theoriacodex.app.di.TheoriaAppContainer
import com.theoriacodex.app.settings.SettingsSitesEntry
import com.theoriacodex.app.source.SiteChecks

@Composable
internal fun rememberCustomSitesEntry(container: TheoriaAppContainer, checks: SiteChecks): SettingsSitesEntry {
    val owner = viewModel(key = "custom-sites") {
        CustomSitesViewModel(container.customSites, checks, removalScope = container.applicationScope)
    }
    val state by owner.state.collectAsStateWithLifecycle()
    return SettingsSitesEntry(summary = customSitesSummary(state.shownSites)) { onBack, accounts ->
        CustomSitesScreen(state, accounts, owner::onAction, onBack)
    }
}
