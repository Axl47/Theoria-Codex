package com.theoriacodex.app.codex

import androidx.compose.runtime.staticCompositionLocalOf
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.Query
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf

data class QuickSaveRecord(
    val post: Post,
    val codexId: String,
    val codexName: String,
    val inserted: Int,
    val savedAtEpochMs: Long,
    val membershipSavedAtEpochMs: Long? = null,
    val createdCodex: Boolean = false,
) {
    val undoable: Boolean get() = inserted == 1
}

data class SaveSuggestion(val codexId: String, val name: String, val cover: List<CodexCoverCandidate>)

data class SaveSuggestions(val targets: List<SaveSuggestion>, val savedIn: SaveSuggestion? = null) {
    companion object {
        val Empty = SaveSuggestions(emptyList())
    }
}

interface QuickSave {
    val lastQuickSave: StateFlow<QuickSaveRecord?>

    suspend fun quickSave(post: Post, codexId: String, query: Query? = null, fromForYou: Boolean = false): QuickSaveRecord?

    suspend fun saveToNew(post: Post, name: String, profileId: String, query: Query? = null, fromForYou: Boolean = false): QuickSaveRecord?

    suspend fun undoQuickSave(record: QuickSaveRecord)

    fun suggestions(post: Post, query: Query? = null, limit: Int = DEFAULT_SUGGESTIONS): Flow<SaveSuggestions>

    companion object {
        const val DEFAULT_SUGGESTIONS = 4
    }
}

private object NoQuickSave : QuickSave {
    override val lastQuickSave: StateFlow<QuickSaveRecord?> = MutableStateFlow<QuickSaveRecord?>(null).asStateFlow()
    override suspend fun quickSave(post: Post, codexId: String, query: Query?, fromForYou: Boolean): QuickSaveRecord? = null
    override suspend fun saveToNew(post: Post, name: String, profileId: String, query: Query?, fromForYou: Boolean): QuickSaveRecord? = null
    override suspend fun undoQuickSave(record: QuickSaveRecord) = Unit
    override fun suggestions(post: Post, query: Query?, limit: Int): Flow<SaveSuggestions> = flowOf(SaveSuggestions.Empty)
}

val LocalQuickSave = staticCompositionLocalOf<QuickSave> { NoQuickSave }
