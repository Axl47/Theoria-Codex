package com.theoriacodex.app.codex

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theoriacodex.data.repository.ClearedLikesSnapshot
import com.theoriacodex.data.repository.CodexCleaningRequest
import com.theoriacodex.data.repository.CodexCleaningSnapshot
import com.theoriacodex.data.repository.CodexRepository
import com.theoriacodex.app.ui.undo.DeferredRemovals
import com.theoriacodex.app.ui.undo.PendingRemoval
import com.theoriacodex.app.ui.undo.UndoAction
import com.theoriacodex.app.ui.undo.UndoAnchor
import com.theoriacodex.app.ui.undo.UndoLifetime
import com.theoriacodex.app.ui.undo.UndoOffer
import com.theoriacodex.app.ui.undo.UndoSlot
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.Codex
import com.theoriacodex.domain.model.CodexAutomaticTag
import com.theoriacodex.domain.model.CodexItem
import com.theoriacodex.domain.model.Post
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface CodexCommand {
    data class Clean(val request: CodexCleaningRequest) : CodexCommand
    data class RestoreCleaned(val snapshot: CodexCleaningSnapshot) : CodexCommand
    data class Create(val id: String, val name: String) : CodexCommand
    data class Rename(val id: String, val name: String) : CodexCommand
    data class AutomaticTag(val id: String, val tag: CodexAutomaticTag, val enabled: Boolean) : CodexCommand
    data class ClearLikes(val profileId: String) : CodexCommand
    data class RestoreLikes(val snapshot: ClearedLikesSnapshot) : CodexCommand
    /** [restoring] marks the Undo of a reorder, which offers no Undo of its own. */
    data class Reorder(val visibleIds: List<String>, val restoring: Boolean = false) : CodexCommand
    data class Remove(val id: String, val items: List<CodexItem>, val posts: List<Post>) : CodexCommand
    data class Restore(val items: List<CodexItem>, val posts: List<Post>) : CodexCommand
}

/** Where Codex offers are drawn: one Codex's detail screen, or the Library's Codices section. */
object CodexUndoAnchors {
    const val DETAIL_SCOPE = "codex-detail"
    const val LIBRARY_SCOPE = "library-codices"
    val Library = UndoAnchor(LIBRARY_SCOPE)
    fun detail(codexId: String) = UndoAnchor(DETAIL_SCOPE, codexId)
}

/** The outcome of one command; [anchor] is where the command was given and where its Undo or Retry is offered. */
data class CodexCommandFeedback(
    val id: Long,
    val command: CodexCommand,
    val succeeded: Boolean,
    val undo: CodexCommand? = null,
    val removedCount: Int = 0,
    val anchor: UndoAnchor = CodexUndoAnchors.Library,
) {
    val action: CodexCommand? get() = if (succeeded) undo else command

    /** A rename or a restored order shows in the Library itself, so its success needs no notice. */
    val quiet: Boolean get() = succeeded && (command is CodexCommand.Rename || command is CodexCommand.Reorder)
    val undoAction: UndoAction? get() = if (!succeeded) UndoAction.Retry else if (undo != null) UndoAction.Undo else null
    val message: String get() = when (command) {
        is CodexCommand.Clean -> when {
            !succeeded -> "Could not clean Codex"
            removedCount == 0 -> "No matching posts to remove"
            else -> "$removedCount ${if (removedCount == 1) "post" else "posts"} removed from Codex"
        }
        is CodexCommand.RestoreCleaned -> if (succeeded) "Cleaned posts restored" else "Could not restore cleaned posts"
        is CodexCommand.Create -> if (succeeded) "Codex created" else "Could not create Codex"
        is CodexCommand.Rename -> if (succeeded) "Codex renamed" else "Could not rename Codex"
        is CodexCommand.AutomaticTag -> if (succeeded) "Automatic tag updated" else "Could not update automatic tag"
        is CodexCommand.ClearLikes -> if (succeeded) "Likes cleared" else "Could not clear Likes"
        is CodexCommand.RestoreLikes -> if (succeeded) "Likes restored" else "Could not restore Likes"
        is CodexCommand.Reorder -> when {
            command.restoring -> if (succeeded) "Order restored" else "Could not restore order"
            else -> if (succeeded) "Codices reordered" else "Could not reorder Codices"
        }
        is CodexCommand.Remove -> if (succeeded) "${command.items.size} ${if (command.items.size == 1) "post" else "posts"} removed" else "Could not remove posts"
        is CodexCommand.Restore -> if (succeeded) "Posts restored" else "Could not restore posts"
    }
}

/**
 * Serial retained mutations with acknowledged feedback; cancelling a UI collector loses no Retry or Undo.
 * Notices without an action are shown as snackbars; Undo and Retry move into the app's [UndoSlot] through
 * [offer], anchored where each command was given.
 *
 * Deleting a Codex is deferred destruction ([pendingDeletes]): it hides at once and is deleted when the Library is
 * left ([commitDeletes]) or this owner is cleared, in [removalScope], which must be application-lived. The commit
 * also stops and drops the Codex's offline copy through [releaseOffline].
 */
class CodexCommandViewModel(
    private val repository: CodexRepository,
    private val clearLikes: suspend (String) -> ClearedLikesSnapshot?,
    private val restoreLikes: suspend (ClearedLikesSnapshot) -> Unit,
    private val commitReorder: suspend (List<String>) -> Unit,
    private val cleaning: CodexCleaningService,
    scopeOverride: CoroutineScope? = null,
    removalScope: CoroutineScope? = null,
    private val releaseOffline: suspend (codexId: String) -> Unit = {},
) : ViewModel() {
    private val scope = scopeOverride ?: viewModelScope
    private val mutex = Mutex()
    private var nextId = 0L
    private val mutableFeedback = MutableStateFlow<List<CodexCommandFeedback>>(emptyList())
    val feedback = mutableFeedback.asStateFlow()
    private val deletions = DeferredRemovals<String>(removalScope ?: scope, ::commitDelete)
    private val mutableReorderFailures = MutableSharedFlow<List<String>>(extraBufferCapacity = 8)

    /** Codices deleted but not yet gone, by id: hidden from the Library and save targets, each tile showing Undo. */
    val pendingDeletes: StateFlow<Map<String, PendingRemoval>> = deletions.pending

    /** Each order whose write failed, so the grid can drop that optimistic order (its Retry is in the slot). */
    val reorderFailures: SharedFlow<List<String>> = mutableReorderFailures.asSharedFlow()

    // Each command takes the anchor of the surface it was given on; Library is where the Codex menus live.
    fun clean(profileId: String, targetId: String, comparisonIds: Set<String>, anchor: UndoAnchor = CodexUndoAnchors.Library) =
        submit(CodexCommand.Clean(CodexCleaningRequest(profileId, targetId, comparisonIds.toSet())), anchor)

    fun create(profileId: String, name: String, anchor: UndoAnchor = CodexUndoAnchors.Library) =
        submit(CodexCommand.Create(profileScopedCodexId(profileId), name), anchor)
    fun rename(id: String, name: String, anchor: UndoAnchor = CodexUndoAnchors.Library) = submit(CodexCommand.Rename(id, name), anchor)
    fun automaticTag(id: String, tag: CodexAutomaticTag, enabled: Boolean, anchor: UndoAnchor = CodexUndoAnchors.Library) =
        submit(CodexCommand.AutomaticTag(id, tag, enabled), anchor)
    fun reorder(ids: List<String>, anchor: UndoAnchor = CodexUndoAnchors.Library) = submit(CodexCommand.Reorder(ids.toList()), anchor)

    /**
     * Deletes Codex [id] (named [name]) until the Library is left, with Undo in its tile; deleting from Codex detail
     * navigates there. Likes is cleared instead, through the slot at [anchor].
     */
    fun delete(id: String, profileId: String, anchor: UndoAnchor = CodexUndoAnchors.Library, name: String? = null) {
        if (id == likesCodexIdForProfile(profileId)) return submit(CodexCommand.ClearLikes(profileId), anchor)
        deletions.remove(id, name?.let { "Deleted $it" } ?: "Deleted Codex", UndoLifetime.UntilLeft)
    }

    /** A deleted Codex tile's Undo, or its Retry after the deletion failed. */
    fun actOnDelete(id: String) = deletions.act(id)

    /** Deletes every Codex still waiting on Undo; the Library calls it when left. */
    fun commitDeletes() = deletions.commitAll()

    override fun onCleared() = deletions.commitAll()

    private suspend fun commitDelete(id: String): Boolean = runCatchingPreservingCancellation {
        mutex.withLock { repository.deleteCodexForUndo(id) }
        releaseOffline(id)
    }.isSuccess

    fun remove(id: String, items: List<CodexItem>, posts: List<Post>, anchor: UndoAnchor = CodexUndoAnchors.detail(id)) {
        val postIds = posts.mapTo(mutableSetOf(), Post::id)
        val selected = items.filter { it.postId in postIds }
        if (selected.isNotEmpty()) submit(CodexCommand.Remove(id, selected, posts.toList()), anchor)
    }

    /** Removes [post] from Codex [id] for a surface that shows the post without the Codex's items, such as the Viewer. */
    fun removePost(id: String, post: Post) {
        scope.launch { remove(id, repository.observeCodexItems(id).first(), listOf(post), UndoAnchor.App) }
    }

    /**
     * Removes [post] from Codex [id] in line with the other commands, for a surface with its own Undo (the triage
     * deck), so no feedback is offered. Returns what puts exactly that membership back, or null when the post was not
     * in the Codex; throws when the write fails.
     */
    suspend fun removeWithOwnUndo(id: String, post: Post): (suspend () -> Unit)? {
        val restore = mutex.withLock {
            val items = repository.observeCodexItems(id).first().filter { it.postId == post.id }
            if (items.isEmpty()) null else execute(CodexCommand.Remove(id, items, listOf(post))).undo
        } ?: return null
        return { mutex.withLock { execute(restore) } }
    }

    /** Dismisses feedback [id] without acting on it. */
    fun acknowledge(id: Long) {
        take(id)
    }

    /** Moves actionable feedback [id] into [undo]; its Undo or Retry then runs here, in this owner's scope. */
    fun offer(id: Long, undo: UndoSlot) {
        val item = take(id) ?: return
        val action = item.action ?: return
        val label = item.undoAction ?: return
        undo.offer(scope, UndoOffer(item.message, item.anchor, label) { submit(action, item.anchor) })
    }

    private fun take(id: Long): CodexCommandFeedback? {
        val item = feedback.value.firstOrNull { it.id == id } ?: return null
        mutableFeedback.update { current -> current.filterNot { it.id == id } }
        return item
    }

    private fun submit(command: CodexCommand, anchor: UndoAnchor) {
        scope.launch {
            mutex.withLock {
                val result = runCatchingPreservingCancellation { execute(command) }
                val outcome = result.getOrNull()
                val feedback = CodexCommandFeedback(
                    ++nextId, command, result.isSuccess, outcome?.undo, outcome?.removedCount ?: 0, anchor,
                )
                mutableFeedback.update { it + feedback }
                if (command is CodexCommand.Reorder && result.isFailure) mutableReorderFailures.tryEmit(command.visibleIds)
            }
        }
    }

    /** Commits [command]'s order and returns the command that puts the previous order back, if it changed. */
    private suspend fun reorder(command: CodexCommand.Reorder): CodexCommand? {
        val moved = command.visibleIds.toSet()
        val previous = repository.observeCodices().first().map(Codex::codexId).filter { it in moved }
        commitReorder(command.visibleIds)
        val restorable = !command.restoring && previous.size == moved.size && previous != command.visibleIds
        return CodexCommand.Reorder(previous, restoring = true).takeIf { restorable }
    }

    private data class Outcome(val undo: CodexCommand? = null, val removedCount: Int = 0)

    private suspend fun execute(command: CodexCommand): Outcome {
        when (command) {
            is CodexCommand.Clean -> {
                val snapshot = cleaning.clean(command.request)
                return Outcome(
                    undo = snapshot.takeIf { it.items.isNotEmpty() }?.let(CodexCommand::RestoreCleaned),
                    removedCount = snapshot.items.size,
                )
            }
            is CodexCommand.RestoreCleaned -> cleaning.restore(command.snapshot)
            is CodexCommand.Create -> repository.ensureCodex(command.id, command.name)
            is CodexCommand.Rename -> repository.renameCodex(command.id, command.name)
            is CodexCommand.AutomaticTag -> repository.setAutomaticTag(command.id, command.tag, command.enabled)
            is CodexCommand.ClearLikes -> return Outcome(clearLikes(command.profileId)?.let(CodexCommand::RestoreLikes))
            is CodexCommand.RestoreLikes -> restoreLikes(command.snapshot)
            is CodexCommand.Reorder -> return Outcome(reorder(command))
            is CodexCommand.Remove -> {
                repository.removeItems(command.id, command.items.mapTo(linkedSetOf()) { it.postId })
                return Outcome(CodexCommand.Restore(command.items, command.posts))
            }
            is CodexCommand.Restore -> repository.restoreItems(command.items, command.posts)
        }
        return Outcome()
    }
}
