package com.theoriacodex.app.codex

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.theoriacodex.domain.model.Codex
import com.theoriacodex.domain.model.CodexAutomaticTag
import com.theoriacodex.domain.model.SourceKey

internal class CodexListUiState(initialCodices: List<Codex>) {
    var showCreateDialog by mutableStateOf(false)
    var renameTarget by mutableStateOf<Codex?>(null)
    var deleteTarget by mutableStateOf<Codex?>(null)
    var actionTarget by mutableStateOf<Codex?>(null)
    var searchSourceTarget by mutableStateOf<Codex?>(null)
    var tagSelectionTarget by mutableStateOf<CodexSourceSelection?>(null)
    var reorderMode by mutableStateOf(false)
    var reorderDraft by mutableStateOf(initialCodices)
    var draggingCodexId by mutableStateOf<String?>(null)
    var draggingIndex by mutableIntStateOf(-1)
    var dragOffsetY by mutableFloatStateOf(0f)
    var itemHeightPx by mutableFloatStateOf(1f)

    fun synchronizeCodices(codices: List<Codex>) {
        if (!reorderMode) {
            reorderDraft = codices
            return
        }
        val current = reorderDraft.mapNotNull { draft ->
            codices.firstOrNull { codex -> codex.codexId == draft.codexId }
        }
        reorderDraft = current + codices.filterNot { codex ->
            current.any { existing -> existing.codexId == codex.codexId }
        }
    }

    fun toggleReorder(codices: List<Codex>, onCommitReorder: (List<String>) -> Unit) {
        if (!reorderMode) {
            reorderDraft = codices
            reorderMode = true
            return
        }
        onCommitReorder(reorderDraft.map(Codex::codexId))
        resetDrag()
        reorderMode = false
    }

    fun startDrag(codexId: String, index: Int) {
        draggingCodexId = codexId
        draggingIndex = index
        dragOffsetY = 0f
    }

    fun drag(codexId: String, fallbackIndex: Int, deltaY: Float) {
        if (draggingCodexId != codexId) return
        dragOffsetY += deltaY
        val currentIndex = draggingIndex.takeIf { it >= 0 } ?: fallbackIndex
        val threshold = itemHeightPx * 0.55f
        when {
            dragOffsetY >= threshold && currentIndex < reorderDraft.lastIndex -> {
                reorderDraft = moveCodex(reorderDraft, currentIndex, currentIndex + 1)
                draggingIndex = currentIndex + 1
                dragOffsetY -= itemHeightPx
            }
            dragOffsetY <= -threshold && currentIndex > 0 -> {
                reorderDraft = moveCodex(reorderDraft, currentIndex, currentIndex - 1)
                draggingIndex = currentIndex - 1
                dragOffsetY += itemHeightPx
            }
        }
    }

    fun resetDrag() {
        draggingCodexId = null
        draggingIndex = -1
        dragOffsetY = 0f
    }
}

internal data class CodexListPresentation(
    val codices: List<Codex>,
    val itemCounts: Map<String, Int>,
    val coverCandidates: Map<String, List<CodexCoverCandidate>>,
    val searchSourceOptions: Map<String, List<CodexSearchSourceOption>>,
    val searchTagOptions: Map<String, Map<SourceKey, List<CodexSearchTagOption>>>,
    val likesCodexId: String,
)

internal data class CodexListActions(
    val openCodex: (String) -> Unit,
    val importCodex: () -> Unit,
    val downloadCodex: (String) -> Unit,
    val shareCodex: (String) -> Unit,
    val searchFromCodex: (String, SourceKey, List<String>) -> Unit,
    val commitReorder: (List<String>) -> Unit,
    val createCodex: (String) -> Unit,
    val renameCodex: (String, String) -> Unit,
    val setAutomaticTag: (String, CodexAutomaticTag, Boolean) -> Unit,
    val deleteCodex: (String) -> Unit,
)

internal data class CodexSourceSelection(val codex: Codex, val source: SourceKey)

internal fun moveCodex(codices: List<Codex>, fromIndex: Int, toIndex: Int): List<Codex> {
    if (fromIndex == toIndex || fromIndex !in codices.indices || toIndex !in codices.indices) return codices
    return codices.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
}
