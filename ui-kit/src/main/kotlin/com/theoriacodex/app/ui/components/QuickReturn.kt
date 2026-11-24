package com.theoriacodex.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.sign

@Composable
fun rememberQuickReturn(onReveal: (Boolean) -> Unit): NestedScrollConnection {
    val currentOnReveal by rememberUpdatedState(onReveal)
    val threshold = with(LocalDensity.current) { QUICK_RETURN_DISTANCE.toPx() }
    return remember(threshold) {
        object : NestedScrollConnection {
            private var travel = 0f

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                val moved = consumed.y
                if (moved == 0f) return Offset.Zero
                if (sign(travel) != sign(moved)) travel = 0f
                travel += moved
                if (abs(travel) >= threshold) {
                    currentOnReveal(travel > 0f)
                    travel = 0f
                }
                return Offset.Zero
            }
        }
    }
}

private val QUICK_RETURN_DISTANCE = 24.dp
