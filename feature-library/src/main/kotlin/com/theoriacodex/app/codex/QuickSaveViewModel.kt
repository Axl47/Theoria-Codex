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

class QuickSaveViewModel(
    private val saver: CodexSaver,
    private val codices: CodexRepository,
    private val cacheFullImage: suspend () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val last = MutableStateFlow<QuickSaveRecord?>(null)
    val lastQuickSave: StateFlow<QuickSaveRecord?> = last.asStateFlow()

    suspend fun quickSave(post: Post, codexId: String, fromForYou: Boolean): QuickSaveRecord? = save(post, codexId, fromForYou, newName = null)

    suspend fun saveToNew(post: Post, name: String, profileId: String, fromForYou: Boolean): QuickSaveRecord? =
        save(post, profileScopedCodexId(profileId), fromForYou, newName = name)

    private suspend fun save(post: Post, codexId: String, fromForYou: Boolean, newName: String?): QuickSaveRecord? = viewModelScope.async {
        val result = saver.save(codexId, listOf(post), cacheFullImage(), fromForYou, newCollectionName = newName)
        if (result !is CodexSaveResult.Success && newName != null) runCatchingPreservingCancellation { codices.deleteCodexIfEmpty(codexId) }
        (result as? CodexSaveResult.Success)?.let { saved ->
            val membership = if (saved.inserted == 1) membershipSavedAt(codexId, post.id) else null
            val name = saver.codexName(codexId) ?: newName ?: "Codex"
            QuickSaveRecord(post, codexId, name, saved.inserted, clock(), membership, createdCodex = newName != null)
                .also { last.value = it }
        }
    }.await()

    suspend fun undoQuickSave(record: QuickSaveRecord) {
        if (!record.undoable) return
        last.compareAndSet(record, null)
        val savedAt = record.membershipSavedAtEpochMs ?: return
        viewModelScope.async {
            runCatchingPreservingCancellation {
                if (membershipSavedAt(record.codexId, record.post.id) == savedAt) codices.removeItems(record.codexId, setOf(record.post.id))
                if (record.createdCodex) codices.deleteCodexIfEmpty(record.codexId)
            }
        }.await()
    }

    private suspend fun membershipSavedAt(codexId: String, postId: PostId): Long? =
        codices.observeCodexItems(codexId).first().firstOrNull { item -> item.postId == postId }?.savedAtEpochMs
}
