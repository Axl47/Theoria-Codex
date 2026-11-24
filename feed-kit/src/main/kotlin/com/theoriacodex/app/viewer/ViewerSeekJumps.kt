package com.theoriacodex.app.viewer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue

@Composable
fun ViewerSeekJumps(serial: Int, deltaMs: Long, enabled: () -> Boolean, onJump: (deltaMs: Long) -> Unit) {
    val handled = remember { intArrayOf(serial) }
    val latestEnabled by rememberUpdatedState(enabled)
    val latestOnJump by rememberUpdatedState(onJump)
    LaunchedEffect(serial) {
        if (serial == handled[0]) return@LaunchedEffect
        handled[0] = serial
        if (latestEnabled() && deltaMs != 0L) latestOnJump(deltaMs)
    }
}
