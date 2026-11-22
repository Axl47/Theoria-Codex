package com.theoriacodex.app.viewer

import com.theoriacodex.app.codex.QuickSaveRecord
import com.theoriacodex.app.codex.SaveSuggestion
import com.theoriacodex.app.codex.SaveSuggestions
import com.theoriacodex.app.codex.SaveTarget
import com.theoriacodex.app.codex.saveMenuTargets
import com.theoriacodex.app.ui.components.CollectTarget
import com.theoriacodex.domain.model.PostId

/** How long the dock's Save button keeps "Saved · Undo" after a one-tap save. */
internal const val VIEWER_SAVED_UNDO_MS = 4_000L

/** Save suggestions and the post they were scored for. */
internal data class ViewerPostSuggestions(val postId: PostId, val suggestions: SaveSuggestions)

/** What the dock's Save button shows for the current post. */
internal sealed interface ViewerSaveButtonState {
    /** Suggestions have not arrived for any post yet. */
    data object Loading : ViewerSaveButtonState

    /**
     * The best Codex the post is not in; [inAnyCodex] adds a small check. Until [ready], [target] is still the previous
     * post's, shown so the button does not flicker but not saved to.
     */
    data class Suggest(val target: SaveSuggestion, val inAnyCodex: Boolean, val ready: Boolean = true) : ViewerSaveButtonState

    /** A one-tap save of this post just landed; tapping undoes it while [QuickSaveRecord.undoable]. */
    data class Saved(val record: QuickSaveRecord) : ViewerSaveButtonState

    /** No Codex to suggest: the profile has none, or the post is already in every one ([inAnyCodex]). */
    data class NoTarget(val inAnyCodex: Boolean) : ViewerSaveButtonState
}

/** What a tap on the Save button, or a release of its fan, does. */
internal sealed interface ViewerSaveCommand {
    data class QuickSave(val codexId: String) : ViewerSaveCommand

    /** Undoes [record], the save the button shows, not whichever save landed last. */
    data class Undo(val record: QuickSaveRecord) : ViewerSaveCommand

    /** The full Save-to-Codex picker. */
    data object OpenPicker : ViewerSaveCommand

    /** Names a new Codex and saves the post into it. */
    data object NewCodex : ViewerSaveCommand
}

/**
 * The Save button's state for [postId]. [latest] are the latest suggestions that arrived, possibly still another post's
 * while this post's load; [lastSave] is the app's latest one-tap save, shown for [VIEWER_SAVED_UNDO_MS] when it was this post.
 */
internal fun viewerSaveButtonState(
    postId: PostId,
    latest: ViewerPostSuggestions?,
    lastSave: QuickSaveRecord?,
    nowEpochMs: Long,
): ViewerSaveButtonState {
    // A clock read just before the save landed counts as "just saved" too.
    if (lastSave != null && lastSave.post.id == postId && nowEpochMs - lastSave.savedAtEpochMs < VIEWER_SAVED_UNDO_MS) {
        return ViewerSaveButtonState.Saved(lastSave)
    }
    val suggestions = latest?.suggestions ?: return ViewerSaveButtonState.Loading
    val ready = latest.postId == postId
    // Whether the post is in every Codex is the previous post's answer until this one's arrive.
    val top = suggestions.targets.firstOrNull()
        ?: return if (ready) ViewerSaveButtonState.NoTarget(suggestions.inAnyCodex) else ViewerSaveButtonState.Loading
    return ViewerSaveButtonState.Suggest(top, inAnyCodex = ready && suggestions.inAnyCodex, ready = ready)
}

/** What tapping the button does in this state. */
internal fun ViewerSaveButtonState.tapCommand(): ViewerSaveCommand = when (this) {
    is ViewerSaveButtonState.Suggest -> if (ready) ViewerSaveCommand.QuickSave(target.codexId) else ViewerSaveCommand.OpenPicker
    is ViewerSaveButtonState.Saved -> if (record.undoable) ViewerSaveCommand.Undo(record) else ViewerSaveCommand.OpenPicker
    ViewerSaveButtonState.Loading -> ViewerSaveCommand.OpenPicker
    // Suggestions never include Likes, so with none and the post in no Codex, the profile has no Codex yet.
    is ViewerSaveButtonState.NoTarget -> if (inAnyCodex) ViewerSaveCommand.OpenPicker else ViewerSaveCommand.NewCodex
}

/**
 * The hold fan for [postId]: the shared save menu ([saveMenuTargets]) over the suggestions after the top one, which the
 * button itself saves to, when they are this post's; otherwise only New and More.
 */
internal fun viewerSaveFanTargets(postId: PostId, latest: ViewerPostSuggestions?): List<CollectTarget<SaveTarget>> =
    saveMenuTargets(latest?.takeIf { it.postId == postId }?.suggestions?.targets.orEmpty().drop(1)) { it }

/**
 * Releasing the fan on [target] saves there, on New names a new Codex, and on More, like off every target, opens the
 * full picker.
 */
internal fun viewerSaveFanRelease(target: SaveTarget?): ViewerSaveCommand = when (target) {
    is SaveTarget.Codex -> ViewerSaveCommand.QuickSave(target.suggestion.codexId)
    SaveTarget.New -> ViewerSaveCommand.NewCodex
    SaveTarget.More, null -> ViewerSaveCommand.OpenPicker
}
