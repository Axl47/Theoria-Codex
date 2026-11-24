package com.theoriacodex.app.settings.sites

import com.theoriacodex.app.source.VERIFY_IN_BROWSER_LABEL
import com.theoriacodex.data.repository.SettingDestinations
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
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
import com.theoriacodex.app.ui.components.AnnouncedText
import com.theoriacodex.app.ui.components.ConfirmDialog
import com.theoriacodex.app.ui.components.EmptyState
import com.theoriacodex.app.ui.undo.DeferredRemovalRow
import com.theoriacodex.app.ui.undo.SurfaceLeaveEffect
import com.theoriacodex.app.ui.components.SettingsHeader
import com.theoriacodex.app.ui.components.SettingsGroup
import com.theoriacodex.app.ui.components.SettingsRowDivider
import com.theoriacodex.app.ui.components.SettingsRow
import com.theoriacodex.app.ui.components.SettingsPageColumn

/** The pages above anything opened from the Sites list. */
internal val SITES_PATH = SETTINGS_PATH + SettingDestinations.Sites.title

/** Settings → Sites: built-in and user-added sites, or the page or add/edit form while one is open. */
@Composable
fun CustomSitesScreen(
    state: CustomSitesUiState,
    accounts: BuiltInAccounts,
    onAction: (CustomSitesAction) -> Unit,
    onBack: () -> Unit,
) {
    // Leaving Sites completes a removal still offered for Undo.
    SurfaceLeaveEffect { onAction(CustomSitesAction.CommitRemovals) }
    BackHandler(enabled = state.form != null) { onAction(CustomSitesAction.CloseForm) }
    BackHandler(enabled = state.form == null && state.openBuiltIn != null) { onAction(CustomSitesAction.CloseBuiltIn) }
    val builtIn = state.builtInSites.firstOrNull { it.source == state.openBuiltIn }
    when (val form = state.form) {
        null -> if (builtIn != null) BuiltInSitePage(builtIn, state.retests[builtIn.source.id], accounts, onAction) else SitesListPage(state, accounts, onAction, onBack)
        is SiteForm.Add -> AddSitePage(form, onAction)
        is SiteForm.Edit -> state.sites.firstOrNull { it.sourceId == form.sourceId }?.let { site ->
            EditSitePage(form, site, state.retests[site.sourceId], site.sourceId in state.workingSiteIds, onAction)
        }
    }
    ReconnectDialog(state.form, onAction)
}

@Composable
private fun SitesListPage(
    state: CustomSitesUiState,
    accounts: BuiltInAccounts,
    onAction: (CustomSitesAction) -> Unit,
    onBack: () -> Unit,
) {
    SettingsPageColumn {
        SettingsHeader(SettingDestinations.Sites.title, onBack, SETTINGS_PATH) {
            IconButton(onClick = { onAction(CustomSitesAction.StartAdd) }) { Icon(Icons.Default.Add, "Add site") }
        }
        state.notice?.let { AnnouncedText(it, Modifier.padding(horizontal = 4.dp), error = true) }
        TestAllStatus(state.testProgress, state.testSummary)
        val testAll: @Composable () -> Unit = {
            TextButton(onClick = { onAction(CustomSitesAction.TestAll) }, enabled = state.testProgress == null) {
                Text("Test all")
            }
        }
        if (state.builtInSites.isNotEmpty()) {
            SettingsGroup("Built-in", action = testAll) {
                state.builtInSites.forEachIndexed { index, site ->
                    if (index > 0) SettingsRowDivider()
                    BuiltInSiteRow(site, state.retests[site.source.id], accounts, onAction)
                }
            }
        }
        if (state.sites.isEmpty()) {
            EmptyState("No added sites")
        } else {
            AddedSitesGroup(state, testAll.takeIf { state.builtInSites.isEmpty() }, onAction)
        }
    }
}

/** The added sites; a removed one leaves its Undo in its row until the removal commits. */
@Composable
private fun AddedSitesGroup(state: CustomSitesUiState, testAll: (@Composable () -> Unit)?, onAction: (CustomSitesAction) -> Unit) {
    SettingsGroup("Added", action = testAll) {
        state.sites.forEachIndexed { index, site ->
            if (index > 0) SettingsRowDivider()
            val removal = state.removals[site.sourceId]
            if (removal != null) {
                DeferredRemovalRow(removal, onAct = { onAction(CustomSitesAction.ActOnRemoval(site.sourceId)) })
            } else {
                SiteRow(site, state.retests[site.sourceId], site.sourceId in state.workingSiteIds, onAction)
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
            trailing = { RetestTrailing(site.label, retest, working) { onAction(CustomSitesAction.Retest(site.sourceId)) } },
        )
        RetestFailure(retest, site.host, onAction, KeyAction(site.sourceId, site.hasCredentials))
    }
}

/** The row's way to the key field of [sourceId]; [hasKey] when one is saved, so the button says to check it. */
internal data class KeyAction(val sourceId: String, val hasKey: Boolean)

/**
 * Why a row's last test failed, with the ways to fix it: a browser check for a challenge, and the key field
 * through [keyAction] when the site refused the keys or an account could get past the challenge.
 */
@Composable
internal fun RetestFailure(retest: RetestStatus?, host: String, onAction: (CustomSitesAction) -> Unit, keyAction: KeyAction? = null) {
    if (retest !is RetestStatus.Done || retest.ok) return
    AnnouncedText(retest.message, Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), error = true)
    val offeredKey = keyAction?.takeIf { retest.needsKey || (retest.blocked && !it.hasKey) }
    Row(Modifier.padding(start = 4.dp)) {
        if (retest.blocked) {
            TextButton(onClick = { onAction(CustomSitesAction.StartVerification(host)) }) { Text(VERIFY_IN_BROWSER_LABEL) }
        }
        offeredKey?.let { action ->
            TextButton(onClick = { onAction(CustomSitesAction.StartEdit(action.sourceId, expandKeys = true)) }) {
                Text(if (action.hasKey) "Check API key" else ADD_API_KEY_LABEL)
            }
        }
    }
}

/** A site row's end: the spinner while [retest] runs, else the Working mark when [working], and Re-test. */
@Composable
internal fun RetestTrailing(label: String, retest: RetestStatus?, working: Boolean, onRetest: () -> Unit) {
    if (retest == RetestStatus.Running) {
        RowSpinner("Checking $label")
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (working) WorkingMark()
            IconButton(onClick = onRetest) { Icon(Icons.Default.Refresh, "Re-test $label") }
        }
    }
}

/** A row's "being tested right now" spinner, sized like the button it stands in for. */
@Composable
internal fun RowSpinner(description: String) {
    CircularProgressIndicator(
        Modifier.padding(12.dp).size(24.dp).semantics { contentDescription = description },
        strokeWidth = 2.dp,
    )
}

/** The status line that says which site Test all is on, then how it went. */
@Composable
private fun TestAllStatus(progress: SitesTestProgress?, summary: String?) {
    when {
        progress != null -> Box(Modifier.padding(start = 4.dp)) { Progress(testProgressLine(progress)) }
        summary != null -> AnnouncedText(summary, Modifier.padding(start = 4.dp))
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
private fun ReconnectDialog(form: SiteForm?, onAction: (CustomSitesAction) -> Unit) {
    val stage = (form as? SiteForm.Add)?.stage as? AddStage.Reconnect ?: return
    ConfirmDialog(
        title = "Reconnect site?",
        text = reconnectConfirmationText(stage.hostLabel),
        confirmLabel = "Reconnect",
        onConfirm = { onAction(CustomSitesAction.ConfirmReconnect) },
        onDismiss = { onAction(CustomSitesAction.DismissReconnect) },
    )
}
