package com.theoriacodex.app.settings.sites

import androidx.compose.runtime.Composable
import com.theoriacodex.app.customsites.BuiltInSiteSummary
import com.theoriacodex.app.settings.BuiltInAccounts
import com.theoriacodex.app.settings.SettingsGroup
import com.theoriacodex.app.settings.SettingsHeader
import com.theoriacodex.app.settings.SettingsPageColumn
import com.theoriacodex.app.settings.SettingsRow

/** A built-in source in the Sites list; opens its page. */
@Composable
internal fun BuiltInSiteRow(site: BuiltInSiteSummary, accounts: BuiltInAccounts, onAction: (CustomSitesAction) -> Unit) {
    SettingsRow(
        title = site.label,
        summary = builtInRowSummary(site, accounts.summary(site.source)),
        onClick = { onAction(CustomSitesAction.OpenBuiltIn(site.source)) },
        trailing = if (site.working) { { WorkingMark() } } else null,
    )
}

/** A built-in source's page: its browser check, and its account when it has one. */
@Composable
internal fun BuiltInSitePage(site: BuiltInSiteSummary, accounts: BuiltInAccounts, onAction: (CustomSitesAction) -> Unit) {
    SettingsPageColumn {
        SettingsHeader(site.label, onBack = { onAction(CustomSitesAction.CloseBuiltIn) }, parents = SITES_PATH)
        SettingsGroup("Browser check") {
            SettingsRow(
                title = "Verify in browser",
                summary = if (site.needsVerification) "This site is asking for a browser check" else "Open this site to pass its browser check",
                onClick = { onAction(CustomSitesAction.StartVerification(site.host, afterRefusal = site.needsVerification)) },
                trailing = if (site.working) { { WorkingMark() } } else null,
            )
        }
        accounts.controls(site.source)
    }
}
