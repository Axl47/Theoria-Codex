package com.theoriacodex.app.viewer

import com.theoriacodex.app.codex.QuickSaveRecord
import com.theoriacodex.app.codex.SaveSuggestion
import com.theoriacodex.app.codex.SaveSuggestions
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
    data object Undo : ViewerSaveCommand

    /** The full Save-to-Codex picker. */
    data object OpenPicker : ViewerSaveCommand

    /** Names a new Codex and saves the post into it. */
    data object NewCodex : ViewerSaveCommand
}

/** A target in the Save button's hold fan: one more suggestion, or a new Codex. */
internal sealed interface ViewerSaveFanTarget {
    data class Codex(val suggestion: SaveSuggestion) : ViewerSaveFanTarget
    data object New : ViewerSaveFanTarget
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
    is ViewerSaveButtonState.Saved -> if (record.undoable) ViewerSaveCommand.Undo else ViewerSaveCommand.OpenPicker
    ViewerSaveButtonState.Loading -> ViewerSaveCommand.OpenPicker
    // Suggestions never include Likes, so with none and the post in no Codex, the profile has no Codex yet.
    is ViewerSaveButtonState.NoTarget -> if (inAnyCodex) ViewerSaveCommand.OpenPicker else ViewerSaveCommand.NewCodex
}

/** The hold fan for [postId]: the suggestions after the top one (at most three) when they are this post's, then New. */
internal fun viewerSaveFanTargets(postId: PostId, latest: ViewerPostSuggestions?): List<ViewerSaveFanTarget> =
    latest?.takeIf { it.postId == postId }?.suggestions?.targets.orEmpty().drop(1).take(VIEWER_SAVE_FAN_CODICES).map(ViewerSaveFanTarget::Codex) + ViewerSaveFanTarget.New

/** Releasing the fan on [target] saves there, or on New names a new Codex; off every target it opens the full picker. */
internal fun viewerSaveFanRelease(target: ViewerSaveFanTarget?): ViewerSaveCommand = when (target) {
    is ViewerSaveFanTarget.Codex -> ViewerSaveCommand.QuickSave(target.suggestion.codexId)
    ViewerSaveFanTarget.New -> ViewerSaveCommand.NewCodex
    null -> ViewerSaveCommand.OpenPicker
}

private const val VIEWER_SAVE_FAN_CODICES = 3
