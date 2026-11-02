package com.theoriacodex.app.codex

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.theoriacodex.domain.model.Codex
import com.theoriacodex.domain.model.CodexAutomaticTag
import com.theoriacodex.domain.model.SourceKey

internal class CodexListUiState(initialCodices: List<Codex>) {
    var cleaningTargetId by mutableStateOf<String?>(null)
    var showCreateDialog by mutableStateOf(false)
    var renameTarget by mutableStateOf<Codex?>(null)
    var deleteTarget by mutableStateOf<Codex?>(null)
    var actionTarget by mutableStateOf<Codex?>(null)
    var searchSourceTarget by mutableStateOf<Codex?>(null)
    var tagSelectionTarget by mutableStateOf<CodexSourceSelection?>(null)
    val ordering = com.theoriacodex.app.ui.components.ReorderListState(initialCodices, Codex::codexId)

    fun synchronizeCodices(codices: List<Codex>) {
        if (codices.none { it.codexId == cleaningTargetId }) cleaningTargetId = null
        ordering.synchronize(codices)
    }

}

internal data class CodexListPresentation(
    val codices: List<Codex>,
    val itemCounts: Map<String, Int>,
    val coverCandidates: Map<String, List<CodexCoverCandidate>>,
    val searchSourceOptions: Map<String, List<CodexSearchSourceOption>>,
    val searchTagOptions: Map<String, Map<SourceKey, List<CodexSearchTagOption>>>,
    val likesCodexId: String,
    val actionLoading: Boolean = false,
    val actionFailed: Boolean = false,
    val retryActionOptions: () -> Unit = {},
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
    val cleanCodex: (String, Set<String>) -> Unit,
    val makeAvailableOffline: ((String) -> Unit)? = null,
)

internal data class CodexSourceSelection(val codex: Codex, val source: SourceKey)

internal fun moveCodex(codices: List<Codex>, fromIndex: Int, toIndex: Int): List<Codex> {
    if (fromIndex == toIndex || fromIndex !in codices.indices || toIndex !in codices.indices) return codices
    return codices.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
}
