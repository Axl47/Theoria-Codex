package com.theoriacodex.app.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The current artwork's accent, shown on progress and on the selected thumbnail. White when the art has none. */
internal val LocalViewerAccent = compositionLocalOf { Color.White }

/** The Viewer's shared look: white controls over soft black scrims, so the artwork stays continuous behind them. */
internal object ViewerVeil {
    val Capsule = Color.Black.copy(alpha = 0.62f)
    val InactiveTrack = Color.White.copy(alpha = 0.32f)
    const val SECONDARY_TEXT_ALPHA = 0.68f

    private val LegibilityShadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 1f), blurRadius = 4f)

    /** Tabular figures keep time readouts from shifting on device fonts whose digits vary in width. */
    val Time = TextStyle(fontSize = 13.sp, fontFeatureSettings = "tnum", shadow = LegibilityShadow)
    val LargeReadout = TextStyle(fontSize = 22.sp, fontFeatureSettings = "tnum", shadow = LegibilityShadow)

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

/** The tall bottom scrim: takes under half the screen, so landscape video keeps most of its frame clear. */
@Composable
private fun ViewerBottomScrim() {
    Box(Modifier.fillMaxWidth().fillMaxHeight(0.42f).heightIn(max = 290.dp).background(ViewerVeil.BottomScrim))
}

/**
 * Backs a player's timeline. It lives inside the player because the timeline is drawn there: a scrim
 * in the screen's overlay would sit on top of the timeline and dim it.
 */
@Composable
internal fun ViewerTimelineVeil(timeline: @Composable () -> Unit) {
    Box(contentAlignment = Alignment.BottomCenter) {
        ViewerBottomScrim()
        timeline()
    }
}

/**
 * Everything under the media that belongs to the screen rather than the player. Pages with a timeline
 * bring their own scrim, so only a faint one is added behind the strip to carry it through loading.
 */
@Composable
internal fun ViewerBottomChrome(
    timelinePresent: Boolean,
    pageCount: Int,
    pageIndex: Int,
    modifier: Modifier = Modifier,
    strip: @Composable () -> Unit,
) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        if (timelinePresent) Box(Modifier.matchParentSize().background(ViewerVeil.StripOnlyScrim)) else ViewerBottomScrim()
        Column(Modifier.fillMaxWidth()) {
            if (!timelinePresent && pageCount > 1) ViewerPageSegments(pageCount, pageIndex)
            strip()
        }
    }
}

/** Multi-page position in the timeline's slot. Long galleries become one continuous bar, since segments would vanish. */
@Composable
private fun ViewerPageSegments(pageCount: Int, pageIndex: Int) {
    val accent = LocalViewerAccent.current
    val slot = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(3.dp).clearAndSetSemantics { }
    if (pageCount > MAX_PAGE_SEGMENTS) {
        ViewerTimelineTrack((pageIndex + 1f) / pageCount, slot, showHandle = false)
        return
    }
    Row(slot, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(pageCount) { page ->
            Box(Modifier.weight(1f).fillMaxHeight().background(if (page == pageIndex) accent else ViewerVeil.InactiveTrack, CircleShape))
        }
    }
}

private const val MAX_PAGE_SEGMENTS = 24
