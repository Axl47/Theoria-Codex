package com.theoriacodex.app.codex

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theoriacodex.data.repository.CodexCleaningRequest
import com.theoriacodex.data.repository.CodexCleaningSnapshot
import com.theoriacodex.data.repository.CodexRepository
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.CodexAutomaticTag
import com.theoriacodex.domain.model.CodexItem
import com.theoriacodex.domain.model.Post
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal sealed interface CodexCommand {
    data class Clean(val request: CodexCleaningRequest) : CodexCommand
    data class RestoreCleaned(val snapshot: CodexCleaningSnapshot) : CodexCommand
    data class Create(val id: String, val name: String) : CodexCommand
    data class Rename(val id: String, val name: String) : CodexCommand
    data class AutomaticTag(val id: String, val tag: CodexAutomaticTag, val enabled: Boolean) : CodexCommand
    data class Delete(val id: String) : CodexCommand
    data class ClearLikes(val profileId: String) : CodexCommand
    data class Reorder(val visibleIds: List<String>) : CodexCommand
    data class Remove(val id: String, val items: List<CodexItem>, val posts: List<Post>) : CodexCommand
    data class Restore(val items: List<CodexItem>, val posts: List<Post>) : CodexCommand
}

internal data class CodexCommandFeedback(
    val id: Long,
    val command: CodexCommand,
    val succeeded: Boolean,
    val undo: CodexCommand? = null,
    val removedCount: Int = 0,
) {
    val action: CodexCommand? get() = if (succeeded) undo else command
    val actionLabel: String? get() = if (!succeeded) "Retry" else if (undo != null) "Undo" else null
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
        is CodexCommand.Delete -> if (succeeded) "Codex deleted" else "Could not delete Codex"
        is CodexCommand.ClearLikes -> if (succeeded) "Likes cleared" else "Could not clear Likes"
        is CodexCommand.Reorder -> if (succeeded) "Codices reordered" else "Could not reorder Codices"
        is CodexCommand.Remove -> if (succeeded) "${command.items.size} posts removed" else "Could not remove posts"
        is CodexCommand.Restore -> if (succeeded) "Posts restored" else "Could not restore posts"
    }
}

/** Serial retained mutations with acknowledged feedback; cancelling a UI collector loses no Retry or Undo. */
internal class CodexCommandViewModel(
    private val repository: CodexRepository,
    private val clearLikes: suspend (String) -> Unit,
    private val commitReorder: suspend (List<String>) -> Unit,
    private val cleaning: CodexCleaningService,
    scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    private val scope = scopeOverride ?: viewModelScope
    private val mutex = Mutex()
    private var nextId = 0L
    private val mutableFeedback = MutableStateFlow<List<CodexCommandFeedback>>(emptyList())
    val feedback = mutableFeedback.asStateFlow()

    fun clean(profileId: String, targetId: String, comparisonIds: Set<String>) = submit(
        CodexCommand.Clean(CodexCleaningRequest(profileId, targetId, comparisonIds.toSet())),
    )

    fun create(profileId: String, name: String) = submit(CodexCommand.Create(profileScopedCodexId(profileId), name))
    fun rename(id: String, name: String) = submit(CodexCommand.Rename(id, name))
    fun automaticTag(id: String, tag: CodexAutomaticTag, enabled: Boolean) = submit(CodexCommand.AutomaticTag(id, tag, enabled))
    fun reorder(ids: List<String>) = submit(CodexCommand.Reorder(ids.toList()))
    fun delete(id: String, profileId: String) = submit(
        if (id == likesCodexIdForProfile(profileId)) CodexCommand.ClearLikes(profileId) else CodexCommand.Delete(id),
    )

    fun remove(id: String, items: List<CodexItem>, posts: List<Post>) {
        val postIds = posts.mapTo(mutableSetOf(), Post::id)
        val selected = items.filter { it.postId in postIds }
        if (selected.isNotEmpty()) submit(CodexCommand.Remove(id, selected, posts.toList()))
    }

    fun acknowledge(id: Long, performAction: Boolean) {
        val item = feedback.value.firstOrNull { it.id == id } ?: return
        mutableFeedback.update { current -> current.filterNot { it.id == id } }
        if (performAction) item.action?.let(::submit)
    }

    private fun submit(command: CodexCommand) {
        scope.launch {
            mutex.withLock {
                val result = runCatchingPreservingCancellation { execute(command) }
                val outcome = result.getOrNull()
                val feedback = CodexCommandFeedback(++nextId, command, result.isSuccess, outcome?.undo, outcome?.removedCount ?: 0)
                mutableFeedback.update { it + feedback }
            }
        }
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
            is CodexCommand.Delete -> repository.deleteCodex(command.id)
            is CodexCommand.ClearLikes -> clearLikes(command.profileId)
            is CodexCommand.Reorder -> commitReorder(command.visibleIds)
            is CodexCommand.Remove -> {
                repository.removeItems(command.id, command.items.mapTo(linkedSetOf()) { it.postId })
                return Outcome(CodexCommand.Restore(command.items, command.posts))
            }
            is CodexCommand.Restore -> repository.restoreItems(command.items, command.posts)
        }
        return Outcome()
    }
}
