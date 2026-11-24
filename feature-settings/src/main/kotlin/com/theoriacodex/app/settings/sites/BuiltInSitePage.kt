package com.theoriacodex.app.settings.sites

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import com.theoriacodex.app.customsites.BuiltInSiteSummary
import com.theoriacodex.app.settings.BuiltInAccounts
import com.theoriacodex.app.source.VERIFY_IN_BROWSER_LABEL
import com.theoriacodex.app.ui.components.SettingsHeader
import com.theoriacodex.app.ui.components.SettingsGroup
import com.theoriacodex.app.ui.components.SettingsRow
import com.theoriacodex.app.ui.components.SettingsPageColumn

@Composable
internal fun BuiltInSiteRow(
    site: BuiltInSiteSummary,
    retest: RetestStatus?,
    accounts: BuiltInAccounts,
    onAction: (CustomSitesAction) -> Unit,
) {
    Column {
        SettingsRow(
            title = site.label,
            summary = builtInRowSummary(site, accounts.summary(site.source)),
            onClick = { onAction(CustomSitesAction.OpenBuiltIn(site.source)) },
            trailing = { RetestTrailing(site.label, retest, site.working) { onAction(CustomSitesAction.Retest(site.source.id)) } },
        )
        RetestFailure(retest, site.host, onAction)
    }
}

@Composable
internal fun BuiltInSitePage(site: BuiltInSiteSummary, retest: RetestStatus?, accounts: BuiltInAccounts, onAction: (CustomSitesAction) -> Unit) {
    SettingsPageColumn {
        SettingsHeader(site.label, onBack = { onAction(CustomSitesAction.CloseBuiltIn) }, parents = SITES_PATH)
        val failure = (retest as? RetestStatus.Done)?.takeUnless { it.ok }?.message
        SettingsGroup(null) {
            SettingsRow(
                title = site.host,
                summary = failure,
                summaryIsError = failure != null,
                trailing = { RetestTrailing(site.label, retest, site.working) { onAction(CustomSitesAction.Retest(site.source.id)) } },
            )
        }
        if (!site.working) {
            SettingsGroup("Browser check") {
                SettingsRow(
                    title = VERIFY_IN_BROWSER_LABEL,
                    summary = "This site is asking for a browser check".takeIf { site.needsVerification },
                    onClick = { onAction(CustomSitesAction.StartVerification(site.host)) },
                )
            }
        }
        accounts.controls(site.source)
    }
}
