package com.theoriacodex.app.viewer

import com.theoriacodex.app.codex.QuickSaveRecord
import com.theoriacodex.app.codex.SaveSuggestion
import com.theoriacodex.app.codex.SaveSuggestions
import com.theoriacodex.app.codex.SaveTarget
import com.theoriacodex.app.codex.saveMenuTargets
import com.theoriacodex.app.ui.components.CollectTarget
import com.theoriacodex.domain.model.PostId

internal const val VIEWER_SAVED_UNDO_MS = 4_000L

internal data class ViewerPostSuggestions(val postId: PostId, val suggestions: SaveSuggestions)

internal sealed interface ViewerSaveButtonState {
    data object Loading : ViewerSaveButtonState

    data class Suggest(val target: SaveSuggestion, val savedIn: SaveSuggestion? = null, val ready: Boolean = true) : ViewerSaveButtonState

    data class Saved(val record: QuickSaveRecord) : ViewerSaveButtonState

    data class NoTarget(val savedIn: SaveSuggestion?) : ViewerSaveButtonState
}

internal sealed interface ViewerSaveCommand {
    data class QuickSave(val codexId: String) : ViewerSaveCommand

    data class Undo(val record: QuickSaveRecord) : ViewerSaveCommand

    data object OpenPicker : ViewerSaveCommand

    data object NewCodex : ViewerSaveCommand
}

internal fun viewerSaveButtonState(
    postId: PostId,
    latest: ViewerPostSuggestions?,
    lastSave: QuickSaveRecord?,
    nowEpochMs: Long,
): ViewerSaveButtonState {
    if (lastSave != null && lastSave.post.id == postId && nowEpochMs - lastSave.savedAtEpochMs < VIEWER_SAVED_UNDO_MS) {
        return ViewerSaveButtonState.Saved(lastSave)
    }
    val suggestions = latest?.suggestions ?: return ViewerSaveButtonState.Loading
    val ready = latest.postId == postId
    val top = suggestions.targets.firstOrNull()
        ?: return if (ready) ViewerSaveButtonState.NoTarget(suggestions.savedIn) else ViewerSaveButtonState.Loading
    return ViewerSaveButtonState.Suggest(top, savedIn = suggestions.savedIn.takeIf { ready }, ready = ready)
}

internal fun ViewerSaveButtonState.savedIn(): SaveSuggestion? = when (this) {
    is ViewerSaveButtonState.Suggest -> savedIn
    is ViewerSaveButtonState.NoTarget -> savedIn
    is ViewerSaveButtonState.Saved, ViewerSaveButtonState.Loading -> null
}

internal fun ViewerSaveButtonState.next(): SaveSuggestion? = (this as? ViewerSaveButtonState.Suggest)?.target?.takeIf { savedIn != null }

internal fun ViewerSaveButtonState.tapCommand(): ViewerSaveCommand = when (this) {
    is ViewerSaveButtonState.Suggest -> if (ready) ViewerSaveCommand.QuickSave(target.codexId) else ViewerSaveCommand.OpenPicker
    is ViewerSaveButtonState.Saved -> if (record.undoable) ViewerSaveCommand.Undo(record) else ViewerSaveCommand.OpenPicker
    ViewerSaveButtonState.Loading -> ViewerSaveCommand.OpenPicker
    is ViewerSaveButtonState.NoTarget -> if (savedIn != null) ViewerSaveCommand.OpenPicker else ViewerSaveCommand.NewCodex
}

internal fun viewerSaveFanTargets(postId: PostId, latest: ViewerPostSuggestions?): List<CollectTarget<SaveTarget>> =
    saveMenuTargets(latest?.takeIf { it.postId == postId }?.suggestions?.targets.orEmpty().drop(1)) { it }

internal fun viewerSaveFanRelease(target: SaveTarget?): ViewerSaveCommand = when (target) {
    is SaveTarget.Codex -> ViewerSaveCommand.QuickSave(target.suggestion.codexId)
    SaveTarget.New -> ViewerSaveCommand.NewCodex
    SaveTarget.More, null -> ViewerSaveCommand.OpenPicker
}
