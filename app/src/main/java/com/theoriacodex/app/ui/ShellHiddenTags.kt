package com.theoriacodex.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theoriacodex.app.recommend.HiddenTagsSheet
import com.theoriacodex.app.settings.SettingsAction
import com.theoriacodex.app.ui.navigation.HiddenTagsOpener
import com.theoriacodex.app.ui.navigation.LocalHiddenTagsOpener

/** Whether the hidden tags sheet is open; provided as [LocalHiddenTagsOpener] by [ShellCompositionLocals]. */
@Stable
internal class HiddenTagsRequests : HiddenTagsOpener {
    var open by mutableStateOf(false)

    override fun openHiddenTags() {
        open = true
    }
}

/** The hidden tags sheet, mounted once above the navigation host, for the active profile. */
@Composable
internal fun ShellHiddenTagsSheet(shell: TheoriaShell) {
    val requests = LocalHiddenTagsOpener.current as? HiddenTagsRequests ?: return
    if (!requests.open) return
    val settings = shell.owners.settings
    val state by settings.state.collectAsStateWithLifecycle()
    HiddenTagsSheet(
        entries = state.activeProfileBlacklist,
        onRemove = { entry -> settings.onAction(SettingsAction.RemoveBlacklistEntry(entry.source, entry.tags)) },
        onDismiss = { requests.open = false },
    )
}
