package com.theoriacodex.app.codex

import androidx.compose.runtime.Composable
import com.theoriacodex.app.ui.components.ListReorderHandle
import com.theoriacodex.domain.model.Codex

@Composable
internal fun CodexReorderHandle(codex: Codex, index: Int, state: CodexListUiState) {
    ListReorderHandle(codex.codexId, codex.name, index, state.ordering)
}
