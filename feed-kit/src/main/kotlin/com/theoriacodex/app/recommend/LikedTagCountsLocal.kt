package com.theoriacodex.app.recommend

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The active profile's [LikedTagCounts] for [postTagSignals], shared app-wide; collect it with
 * `collectAsStateWithLifecycle()`. Outside the app shell it stays empty.
 */
val LocalLikedTagCounts = staticCompositionLocalOf<StateFlow<LikedTagCounts>> { MutableStateFlow(LikedTagCounts.Empty) }
