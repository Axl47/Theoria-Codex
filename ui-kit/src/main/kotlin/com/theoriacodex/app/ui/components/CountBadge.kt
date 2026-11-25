package com.theoriacodex.app.ui.components

import androidx.compose.material3.Badge
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics

@Composable
fun CountBadge(count: Int, modifier: Modifier = Modifier) {
    Badge(modifier) {
        Text(if (count > MAX_BADGE_COUNT) "$MAX_BADGE_COUNT+" else "$count", Modifier.semantics { hideFromAccessibility() })
    }
}

private const val MAX_BADGE_COUNT = 99
