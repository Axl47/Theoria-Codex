package com.theoriacodex.app.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theoriacodex.app.ui.theme.TextOverMediaShadow

val LocalViewerAccent = compositionLocalOf { Color.White }

object ViewerVeil {
    val Capsule = Color.Black.copy(alpha = 0.62f)
    val InactiveTrack = Color.White.copy(alpha = 0.32f)
    const val SECONDARY_TEXT_ALPHA = 0.68f

    val Time = TextStyle(fontSize = 13.sp, fontFeatureSettings = "tnum", shadow = TextOverMediaShadow)
    val LargeReadout = TextStyle(fontSize = 22.sp, fontFeatureSettings = "tnum", shadow = TextOverMediaShadow)

    val TopScrim = Brush.verticalGradient(
        0f to Color.Black.copy(alpha = 0.52f), 0.22f to Color.Black.copy(alpha = 0.4f), 0.45f to Color.Black.copy(alpha = 0.22f),
        0.68f to Color.Black.copy(alpha = 0.09f), 0.85f to Color.Black.copy(alpha = 0.03f), 1f to Color.Transparent,
    )
    val BottomScrim = Brush.verticalGradient(
        0f to Color.Transparent, 0.16f to Color.Black.copy(alpha = 0.07f), 0.4f to Color.Black.copy(alpha = 0.26f),
        0.66f to Color.Black.copy(alpha = 0.56f), 1f to Color.Black.copy(alpha = 0.72f),
    )
    val StripOnlyScrim = Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.3f))
}

@Composable
private fun ViewerBottomScrim() {
    Box(Modifier.fillMaxWidth().fillMaxHeight(0.42f).heightIn(max = 290.dp).background(ViewerVeil.BottomScrim))
}

@Composable
fun ViewerTimelineVeil(timeline: @Composable () -> Unit) {
    Box(contentAlignment = Alignment.BottomCenter) {
        ViewerBottomScrim()
        timeline()
    }
}

@Composable
fun ViewerBottomChrome(
    timelinePresent: Boolean,
    fadesWhileScrubbing: Modifier,
    lift: Dp,
    modifier: Modifier = Modifier,
    pageBar: @Composable () -> Unit,
    strip: @Composable () -> Unit,
) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        if (timelinePresent) Box(Modifier.matchParentSize().then(fadesWhileScrubbing).background(ViewerVeil.StripOnlyScrim)) else ViewerBottomScrim()
        Column(Modifier.fillMaxWidth().padding(bottom = lift)) {
            Box(fadesWhileScrubbing) { strip() }
            if (!timelinePresent) pageBar()
        }
    }
}
