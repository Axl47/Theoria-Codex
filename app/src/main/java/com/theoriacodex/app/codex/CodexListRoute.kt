package com.theoriacodex.app.codex

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theoriacodex.domain.model.Codex
import com.theoriacodex.domain.model.CodexAutomaticTag
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

@Composable
internal fun CodexListScreen(
    codices: List<Codex>,
    itemCounts: Map<String, Int>,
    codexCoverCandidates: Map<String, List<CodexCoverCandidate>>,
    observeActionOptions: (String) -> Flow<CodexActionOptions>,
    onOpenCodex: (String) -> Unit,
    onImportCodex: () -> Unit,
    onDownloadCodex: (String) -> Unit,
    onShareCodex: (String) -> Unit,
    onSearchFromCodex: (String, SourceKey, List<String>) -> Unit,
    onCommitReorder: (List<String>) -> Unit,
    onCreateCodex: (String) -> Unit,
    onRenameCodex: (String, String) -> Unit,
    onSetAutomaticTag: (String, CodexAutomaticTag, Boolean) -> Unit,
    onDeleteCodex: (String) -> Unit,
    likesCodexId: String,
    onMakeAvailableOffline: ((String) -> Unit)? = null,
) {
    val state = remember { CodexListUiState(codices) }
    val targetId = state.actionTarget?.codexId ?: state.searchSourceTarget?.codexId
        ?: state.tagSelectionTarget?.codex?.codexId
    var retry by remember(targetId) { mutableIntStateOf(0) }
    val options = key(targetId, retry) { rememberCodexActionOptions(targetId, observeActionOptions) }
    val presentation = CodexListPresentation(
        codices, itemCounts, codexCoverCandidates,
        targetId?.let { mapOf(it to options.options.sources) }.orEmpty(),
        targetId?.let { mapOf(it to options.options.tags) }.orEmpty(), likesCodexId,
        actionLoading = options.loading,
        actionFailed = options.failed,
        retryActionOptions = { retry++ },
    )
    val actions = CodexListActions(
        onOpenCodex, onImportCodex, onDownloadCodex, onShareCodex, onSearchFromCodex,
        onCommitReorder, onCreateCodex, onRenameCodex, onSetAutomaticTag, onDeleteCodex,
        onMakeAvailableOffline,
    )
    LaunchedEffect(codices, state.reorderMode) { state.synchronizeCodices(codices) }
    CodexListContent(presentation, state, actions)
    CodexListOverlays(presentation, state, actions)
}

private data class CodexActionLoad(
    val options: CodexActionOptions = CodexActionOptions(),
    val loading: Boolean = false,
    val failed: Boolean = false,
)

@Composable
private fun rememberCodexActionOptions(
    codexId: String?,
    observe: (String) -> Flow<CodexActionOptions>,
): CodexActionLoad {
    if (codexId == null) return CodexActionLoad()
    val flow = remember(codexId, observe) {
        observe(codexId).map { CodexActionLoad(options = it) }
            .catch { emit(CodexActionLoad(failed = true)) }
    }
    return flow.collectAsStateWithLifecycle(initialValue = CodexActionLoad(loading = true)).value
}
