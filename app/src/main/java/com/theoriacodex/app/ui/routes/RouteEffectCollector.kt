package com.theoriacodex.app.ui.routes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect

/**
 * Collects one-shot route effects only while the destination can safely host UI work.
 *
 * The latest callback is used without restarting collection, so recomposition cannot lose a
 * buffered effect or make a route repeat navigation merely because its shell callbacks changed.
 */
@Composable
internal fun <Effect : Any> CollectRouteEffects(
    effects: Flow<Effect>,
    onEffect: suspend (Effect) -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnEffect by rememberUpdatedState(onEffect)

    LaunchedEffect(effects, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            effects.collect { effect -> currentOnEffect(effect) }
        }
    }
}
