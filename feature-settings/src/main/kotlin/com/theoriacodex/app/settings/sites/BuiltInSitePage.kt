package com.theoriacodex.app.settings.sites

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import com.theoriacodex.app.customsites.BuiltInSiteSummary
import com.theoriacodex.app.settings.BuiltInAccounts
import com.theoriacodex.app.settings.SettingsGroup
import com.theoriacodex.app.settings.SettingsHeader
import com.theoriacodex.app.settings.SettingsPageColumn
import com.theoriacodex.app.settings.SettingsRow

/** A built-in source in the Sites list, with its last test's result; opens its page. */
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
            trailing = when {
                retest == RetestStatus.Running -> { { RowSpinner("Checking ${site.label}") } }
                site.working -> { { WorkingMark() } }
                else -> null
            },
        )
        RetestFailure(retest, site.host, onAction)
    }
}

/** A built-in source's page: its browser check, and its account when it has one. */
@Composable
internal fun BuiltInSitePage(site: BuiltInSiteSummary, accounts: BuiltInAccounts, onAction: (CustomSitesAction) -> Unit) {
    SettingsPageColumn {
        SettingsHeader(site.label, onBack = { onAction(CustomSitesAction.CloseBuiltIn) }, parents = SITES_PATH)
        SettingsGroup("Browser check") {
            SettingsRow(
                title = VERIFY_IN_BROWSER_LABEL,
                summary = if (site.needsVerification) "This site is asking for a browser check" else "Open this site to pass its browser check",
                onClick = { onAction(CustomSitesAction.StartVerification(site.host, afterRefusal = site.needsVerification)) },
                trailing = if (site.working) { { WorkingMark() } } else null,
            )
        }
        accounts.controls(site.source)
    }
}
