package com.theoriacodex.app.viewer

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

/** How much of the Viewer's chrome (its dock or nav bar) sits under the player's timeline; zero while the chrome is hidden. */
val LocalViewerTimelineInset = staticCompositionLocalOf { 0.dp }

/** Total bottom chrome height (dock/nav bar + timeline + episode/strip bar) that Viewer subtitles stay clear of while chrome is visible. */
val LocalViewerSubtitleBottomLift = staticCompositionLocalOf { 0.dp }
