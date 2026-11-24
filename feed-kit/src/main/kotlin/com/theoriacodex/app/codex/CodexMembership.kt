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

val LocalCodexPostIds = staticCompositionLocalOf<State<Set<PostId>>> { mutableStateOf(emptySet()) }

@Composable
@ReadOnlyComposable
fun codexSavedColor(): Color =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) Color(0xFF81C784) else Color(0xFF256A2B)
