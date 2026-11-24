package com.theoriacodex.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry

internal class CodexTagSheetRequest(val requested: Boolean, val onHandled: () -> Unit)

@Composable
internal fun NavBackStackEntry.codexTagSheetRequest(): CodexTagSheetRequest {
    val requested by savedStateHandle.getStateFlow(CODEX_TAG_SHEET_REQUEST, false).collectAsStateWithLifecycle()
    return CodexTagSheetRequest(requested) { savedStateHandle[CODEX_TAG_SHEET_REQUEST] = false }
}

internal fun TheoriaShell.openCodexTagSheet(codexId: String) {
    val current = navController.currentBackStackEntry
    val showing = current?.destination?.route == AppRoute.CodexDetail && current.arguments?.getString("codexId") == codexId
    if (!showing) navController.navigate(AppRoute.codexDetail(codexId))
    navController.currentBackStackEntry?.savedStateHandle?.set(CODEX_TAG_SHEET_REQUEST, true)
}

private const val CODEX_TAG_SHEET_REQUEST = "codex-tag-sheet-request"
