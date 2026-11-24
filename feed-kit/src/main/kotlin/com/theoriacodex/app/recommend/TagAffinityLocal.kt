package com.theoriacodex.app.recommend

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

val LocalTagAffinityInputs = staticCompositionLocalOf<StateFlow<TagAffinityInputs>> { MutableStateFlow(TagAffinityInputs.Empty) }
