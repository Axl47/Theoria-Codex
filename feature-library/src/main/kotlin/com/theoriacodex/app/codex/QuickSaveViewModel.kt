package com.theoriacodex.app.codex

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theoriacodex.app.codex.transfer.CodexSaveResult
import com.theoriacodex.data.repository.CodexRepository
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * Activity-owned one-tap saves and their silent undo, behind [QuickSave]. Saves run in this owner's scope so
 * they finish when the surface that started them closes.
 */
class QuickSaveViewModel(
    private val saver: CodexSaver,
    private val codices: CodexRepository,
    private val cacheFullImage: suspend () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val last = MutableStateFlow<QuickSaveRecord?>(null)
    val lastQuickSave: StateFlow<QuickSaveRecord?> = last.asStateFlow()

    /** Saves [post] to [codexId] the way the picker does; null when the save failed. */
    suspend fun quickSave(post: Post, codexId: String, fromForYou: Boolean): QuickSaveRecord? = save(post, codexId, fromForYou, newName = null)

    /** Creates a Codex named [name] in [profileId] and saves [post] there; null when either failed. */
    suspend fun saveToNew(post: Post, name: String, profileId: String, fromForYou: Boolean): QuickSaveRecord? =
        save(post, profileScopedCodexId(profileId), fromForYou, newName = name)

    private suspend fun save(post: Post, codexId: String, fromForYou: Boolean, newName: String?): QuickSaveRecord? = viewModelScope.async {
        val result = saver.save(codexId, listOf(post), cacheFullImage(), fromForYou, newCollectionName = newName)
        (result as? CodexSaveResult.Success)?.let { saved ->
            val membership = if (saved.inserted == 1) membershipSavedAt(codexId, post.id) else null
            val name = newName ?: saver.codexName(codexId) ?: "Codex"
            QuickSaveRecord(post, codexId, name, saved.inserted, clock(), membership, createdCodex = newName != null)
                .also { last.value = it }
        }
    }.await()

    /**
     * Removes the membership [record] added while it is still that one, so a later save of the post to the same Codex
     * stays, then the Codex itself when [record] created it and nothing else has been saved there since; forgets [record]
     * as the last quick save only while it still is. Returns once the removal has run, which finishes even if the caller
     * is cancelled.
     */
    suspend fun undoQuickSave(record: QuickSaveRecord) {
        if (!record.undoable) return
        last.compareAndSet(record, null)
        val savedAt = record.membershipSavedAtEpochMs ?: return
        viewModelScope.async {
            runCatchingPreservingCancellation {
                if (membershipSavedAt(record.codexId, record.post.id) == savedAt) codices.removeItems(record.codexId, setOf(record.post.id))
                if (record.createdCodex && codices.observeCodexItems(record.codexId).first().isEmpty()) codices.deleteCodexForUndo(record.codexId)
            }
        }.await()
    }

    /** When [codexId]'s membership of [postId] was stamped, or null when the Codex does not hold it. */
    private suspend fun membershipSavedAt(codexId: String, postId: PostId): Long? =
        codices.observeCodexItems(codexId).first().firstOrNull { item -> item.postId == postId }?.savedAtEpochMs
}
