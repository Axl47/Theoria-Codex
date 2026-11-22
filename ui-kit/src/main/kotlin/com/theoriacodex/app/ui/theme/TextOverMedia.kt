package com.theoriacodex.app.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow

/** Keeps white text readable over media and scrims of any brightness. */
val TextOverMediaShadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 1f), blurRadius = 4f)
