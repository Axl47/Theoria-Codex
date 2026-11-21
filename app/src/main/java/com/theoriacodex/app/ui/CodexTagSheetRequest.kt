package com.theoriacodex.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry

/** A request, held in a Codex detail entry's saved state, that the screen open its tag sheet. */
internal class CodexTagSheetRequest(val requested: Boolean, val onHandled: () -> Unit)

/** Reads this Codex detail entry's tag sheet request; handling it clears it, so recreation does not reopen the sheet. */
@Composable
internal fun NavBackStackEntry.codexTagSheetRequest(): CodexTagSheetRequest {
    val requested by savedStateHandle.getStateFlow(CODEX_TAG_SHEET_REQUEST, false).collectAsStateWithLifecycle()
    return CodexTagSheetRequest(requested) { savedStateHandle[CODEX_TAG_SHEET_REQUEST] = false }
}

/**
 * Opens Codex [codexId] filtered by tag, with its tag sheet showing: on the current screen when it already shows that
 * Codex (its More sheet's Filter by tag), otherwise on a newly opened one (a Library tile's).
 */
internal fun TheoriaShell.openCodexTagSheet(codexId: String) {
    val current = navController.currentBackStackEntry
    val showing = current?.destination?.route == AppRoute.CodexDetail && current.arguments?.getString("codexId") == codexId
    if (!showing) navController.navigate(AppRoute.codexDetail(codexId))
    // Navigating pushes the new entry at once, so the request lands on the screen it opened.
    navController.currentBackStackEntry?.savedStateHandle?.set(CODEX_TAG_SHEET_REQUEST, true)
}

private const val CODEX_TAG_SHEET_REQUEST = "codex-tag-sheet-request"
