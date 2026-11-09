package com.theoriacodex.app.settings.sites

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.customsites.CustomSiteSummary
import com.theoriacodex.app.settings.BuiltInAccounts
import com.theoriacodex.app.settings.SETTINGS_PATH
import com.theoriacodex.app.settings.SettingsGroup
import com.theoriacodex.app.settings.SettingsHeader
import com.theoriacodex.app.settings.SettingsPageColumn
import com.theoriacodex.app.settings.SettingsRow
import com.theoriacodex.app.settings.SettingsRowDivider

/** The pages above anything opened from the Sites list. */
internal val SITES_PATH = SETTINGS_PATH + "Sites"

/** Settings → Sites: built-in and user-added sites, or the page or add/edit form while one is open. */
@Composable
internal fun CustomSitesScreen(
    state: CustomSitesUiState,
    accounts: BuiltInAccounts,
    onAction: (CustomSitesAction) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(enabled = state.form != null) { onAction(CustomSitesAction.CloseForm) }
    BackHandler(enabled = state.form == null && state.openBuiltIn != null) { onAction(CustomSitesAction.CloseBuiltIn) }
    state.verification?.let { verification ->
        SiteVerificationPage(verification, verificationSiteName(state, verification.host), onAction)
        return
    }
    val builtIn = state.builtInSites.firstOrNull { it.source == state.openBuiltIn }
    when (val form = state.form) {
        null -> if (builtIn != null) BuiltInSitePage(builtIn, accounts, onAction) else SitesListPage(state, accounts, onAction, onBack)
        is SiteForm.Add -> AddSitePage(form, onAction)
        is SiteForm.Edit -> state.sites.firstOrNull { it.sourceId == form.sourceId }?.let { site ->
            EditSitePage(form, site, state.retests[site.sourceId], site.sourceId in state.workingSiteIds, onAction)
        }
    }
    RemoveSiteDialog(state, onAction)
    ReconnectDialog(state.form, onAction)
}

/** The page title's name for the site being verified: its label, or its address while it is being added. */
private fun verificationSiteName(state: CustomSitesUiState, host: String): String =
    state.sites.firstOrNull { it.host == host }?.label
        ?: state.builtInSites.firstOrNull { it.host == host }?.label
        ?: host

@Composable
private fun SitesListPage(
    state: CustomSitesUiState,
    accounts: BuiltInAccounts,
    onAction: (CustomSitesAction) -> Unit,
    onBack: () -> Unit,
) {
    SettingsPageColumn {
        SettingsHeader("Sites", onBack, SETTINGS_PATH) {
            IconButton(onClick = { onAction(CustomSitesAction.StartAdd) }) { Icon(Icons.Default.Add, "Add site") }
        }
        state.notice?.let { AnnouncedText(it, Modifier.padding(horizontal = 4.dp), error = true) }
        if (state.builtInSites.isNotEmpty()) {
            SettingsGroup("Built-in") {
                state.builtInSites.forEachIndexed { index, site ->
                    if (index > 0) SettingsRowDivider()
                    BuiltInSiteRow(site, accounts, onAction)
                }
            }
        }
        if (state.sites.isEmpty()) {
            Text(
                "Add a booru by its address, like safebooru.org. Theoria checks which kind of site it is " +
                    "and adds it as a source.",
                modifier = Modifier.padding(horizontal = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = { onAction(CustomSitesAction.StartAdd) }) { Text("Add site") }
        } else {
            SettingsGroup("Added") {
                state.sites.forEachIndexed { index, site ->
                    if (index > 0) SettingsRowDivider()
                    SiteRow(site, state.retests[site.sourceId], site.sourceId in state.workingSiteIds, onAction)
                }
            }
        }
    }
}

@Composable
private fun SiteRow(site: CustomSiteSummary, retest: RetestStatus?, working: Boolean, onAction: (CustomSitesAction) -> Unit) {
    Column {
        SettingsRow(
            title = site.label,
            summary = siteRowSummary(site),
            onClick = { onAction(CustomSitesAction.StartEdit(site.sourceId)) },
            trailing = {
                if (retest == RetestStatus.Running) {
                    CircularProgressIndicator(
                        Modifier.padding(12.dp).size(24.dp).semantics { contentDescription = "Checking ${site.label}" },
                        strokeWidth = 2.dp,
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (working) WorkingMark()
                        IconButton(onClick = { onAction(CustomSitesAction.Retest(site.sourceId)) }) {
                            Icon(Icons.Default.Refresh, "Re-test ${site.label}")
                        }
                    }
                }
            },
        )
        if (retest is RetestStatus.Done && !retest.ok) {
            AnnouncedText(
                retest.message,
                Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                error = true,
            )
            if (retest.blocked) {
                TextButton(
                    onClick = { onAction(CustomSitesAction.StartVerification(site.host, afterRefusal = true)) },
                    modifier = Modifier.padding(start = 4.dp),
                ) { Text("Verify in browser") }
            }
        }
    }
}

/** A site's "proven to work this session" mark; TalkBack reads it as "Working" when it appears. */
@Composable
internal fun WorkingMark() {
    Icon(
        Icons.Default.CheckCircle,
        contentDescription = "Working",
        modifier = Modifier.size(24.dp).semantics { liveRegion = LiveRegionMode.Polite },
        tint = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun RemoveSiteDialog(state: CustomSitesUiState, onAction: (CustomSitesAction) -> Unit) {
    val site = state.removeTarget ?: return
    AlertDialog(
        onDismissRequest = { onAction(CustomSitesAction.DismissRemove) },
        title = { Text("Remove site?") },
        text = { Text(removeConfirmationText(site.label)) },
        dismissButton = {
            TextButton(onClick = { onAction(CustomSitesAction.DismissRemove) }, enabled = !state.removing) {
                Text("Cancel")
            }
        },
        confirmButton = {
            TextButton(onClick = { onAction(CustomSitesAction.ConfirmRemove) }, enabled = !state.removing) {
                Text("Remove", color = MaterialTheme.colorScheme.error)
            }
        },
    )
}

@Composable
private fun ReconnectDialog(form: SiteForm?, onAction: (CustomSitesAction) -> Unit) {
    val stage = (form as? SiteForm.Add)?.stage as? AddStage.Reconnect ?: return
    AlertDialog(
        onDismissRequest = { onAction(CustomSitesAction.DismissReconnect) },
        title = { Text("Reconnect site?") },
        text = { Text(reconnectConfirmationText(stage.hostLabel)) },
        dismissButton = { TextButton(onClick = { onAction(CustomSitesAction.DismissReconnect) }) { Text("Cancel") } },
        confirmButton = { TextButton(onClick = { onAction(CustomSitesAction.ConfirmReconnect) }) { Text("Reconnect") } },
    )
}
