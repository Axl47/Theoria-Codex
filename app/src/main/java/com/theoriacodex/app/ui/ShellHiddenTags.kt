package com.theoriacodex.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theoriacodex.app.recommend.HiddenTagsSheet
import com.theoriacodex.app.recommend.HiddenTagsUndoAnchor
import com.theoriacodex.app.ui.navigation.HiddenTagsOpener
import com.theoriacodex.app.ui.navigation.LocalHiddenTagsOpener
import com.theoriacodex.app.ui.undo.LocalUndoSlot
import com.theoriacodex.app.ui.undo.UndoOffer
import com.theoriacodex.data.repository.AppSettings
import kotlinx.coroutines.launch

/** Whether the hidden tags sheet is open; provided as [LocalHiddenTagsOpener] by [ShellCompositionLocals]. */
@Stable
internal class HiddenTagsRequests : HiddenTagsOpener {
    var open by mutableStateOf(false)

    override fun openHiddenTags() {
        open = true
    }
}

/**
 * The hidden tags sheet, mounted once above the navigation host, for the active profile. Removing an entry
 * applies at once and offers Undo in the sheet, which puts the entry back for the profile it was removed from.
 */
@Composable
internal fun ShellHiddenTagsSheet(shell: TheoriaShell) {
    val requests = LocalHiddenTagsOpener.current as? HiddenTagsRequests ?: return
    if (!requests.open) return
    val repository = shell.data.settingsRepository
    val settings by repository.observeSettings().collectAsStateWithLifecycle(null)
    val profileId = settings?.activeProfileId
    val undo = LocalUndoSlot.current
    // Not the sheet's scope: closing the sheet, or a rotation, must not drop the Undo.
    val scope = undo.scope
    HiddenTagsSheet(
        entries = settings?.activeHiddenTags().orEmpty(),
        onRemove = { entry ->
            profileId?.let { profile ->
                scope.launch {
                    if (!repository.removeForYouBlacklistEntry(profile, entry.source, entry.tags)) return@launch
                    undo.offer(scope, UndoOffer("Unhid ${entry.tags.joinToString(" + ")}", HiddenTagsUndoAnchor) {
                        repository.addForYouBlacklistEntry(profile, entry.source, entry.tags)
                    })
                }
            }
        },
        onDismiss = { requests.open = false },
    )
}

/** The active profile's hidden entries in a stable order. */
private fun AppSettings.activeHiddenTags() = forYouBlacklistByProfile[activeProfileId].orEmpty()
    .sortedWith(compareBy({ it.source.id }, { it.tags.joinToString(" ") }))
