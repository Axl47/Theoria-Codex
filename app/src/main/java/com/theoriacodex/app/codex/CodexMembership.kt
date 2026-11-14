package com.theoriacodex.app.codex

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.theoriacodex.domain.model.PostId

/**
 * Posts in a Codex people made themselves (Likes aside), provided once for the app so every post menu can mark them.
 * A [State] keeps updates from recomposing the provider; only the menus that read it change.
 */
internal val LocalCodexPostIds = staticCompositionLocalOf<State<Set<PostId>>> { mutableStateOf(emptySet()) }

/** Marks a post that is already in a Codex, readable on both light and dark surfaces. */
@Composable
@ReadOnlyComposable
internal fun codexSavedColor(): Color =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) Color(0xFF81C784) else Color(0xFF256A2B)
