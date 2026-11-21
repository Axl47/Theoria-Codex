package com.theoriacodex.app.viewer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue

/**
 * Applies each double-tap seek jump ([serial], [deltaMs]) once. Only a serial this renderer has not seen runs
 * [onJump], so rebinding a player, learning a duration or becoming active again never replays the last jump;
 * a jump that arrives while [enabled] returns false (inactive, failed, scrubbing) is dropped. [enabled] is read when
 * the jump arrives, so scrubbing state never recomposes the caller.
 */
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
