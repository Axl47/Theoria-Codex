package com.theoriacodex.app.codex

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.theoriacodex.data.repository.CodexLibrarySort
import com.theoriacodex.domain.model.Codex
import com.theoriacodex.domain.model.CodexAutomaticTag
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.app.ui.undo.PendingRemoval
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

internal class CodexListUiState {
    var cleaningTargetId by mutableStateOf<String?>(null)
    var showCreateDialog by mutableStateOf(false)
    var renameTarget by mutableStateOf<Codex?>(null)
    var deleteTarget by mutableStateOf<Codex?>(null)
    var actionTarget by mutableStateOf<Codex?>(null)
    var autoSaveTarget by mutableStateOf<Codex?>(null)

    fun synchronizeCodices(codices: List<Codex>) {
        if (codices.none { it.codexId == cleaningTargetId }) cleaningTargetId = null
    }
}

internal data class CodexListPresentation(
    val codices: List<Codex>,
    val itemCounts: Map<String, Int>,
    val coverCandidates: Map<String, List<CodexCoverCandidate>>,
    val searchTagOptions: Map<String, Map<SourceKey, List<CodexSearchTagOption>>>,
    val likesCodexId: String,
    val actionLoading: Boolean = false,
    val actionFailed: Boolean = false,
    val retryActionOptions: () -> Unit = {},
    val librarySort: CodexLibrarySort = CodexLibrarySort.MANUAL,
    val libraryUsage: Map<String, CodexUsage>? = null,
    val observeCleaningMatches: (String, List<String>) -> Flow<Map<String, Set<PostId>>> = { _, _ -> flowOf(emptyMap()) },
    val pendingDeletes: Map<String, PendingRemoval> = emptyMap(),
    val reorderFailures: Flow<List<String>> = emptyFlow(),
    val renameInPlace: Boolean = false,
) {
    val libraryCodices: List<Codex> get() = codices.filterNot { it.codexId == likesCodexId }

    val awaitingSortInputs: Boolean get() {
        val awaitingCounts = librarySort.usesItemCounts && libraryCodices.any { it.codexId !in itemCounts }
        return awaitingCounts || librarySort.usesUsage && libraryUsage == null
    }
}

internal data class CodexListActions(
    val openCodex: (String) -> Unit,
    val importCodex: () -> Unit,
    val downloadCodex: (String) -> Unit,
    val shareCodex: (String) -> Unit,
    val filterByTag: (String) -> Unit,
    val commitReorder: (List<String>) -> Unit,
    val createCodex: (String) -> Unit,
    val renameCodex: (String, String) -> Unit,
    val setAutomaticTag: (String, CodexAutomaticTag, Boolean) -> Unit,
    val deleteCodex: (String) -> Unit,
    val cleanCodex: (String, Set<String>) -> Unit,
    val makeAvailableOffline: ((String) -> Unit)? = null,
    val setLibrarySort: (CodexLibrarySort) -> Unit = {},
    val actOnDelete: (String) -> Unit = {},
    val leaveLibrary: () -> Unit = {},
)
