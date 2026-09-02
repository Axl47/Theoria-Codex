package com.theoriacodex.app.viewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.viewer.ocr.ViewerOcrTranslationUiState
import com.theoriacodex.app.viewer.ocr.ViewerTranslationCardState
import com.theoriacodex.data.repository.ViewerOcrLanguage
import kotlin.math.roundToInt

@Composable
internal fun ViewerOcrHighlightLayer(
    state: ViewerOcrTranslationUiState,
    onRegionActivated: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.regions.isEmpty() || state.imageWidth <= 0 || state.imageHeight <= 0) return
    val fill = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.22f)
    val outline = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.9f)
    val selectedId = state.translationCard?.regionId
    val selectedFill = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
    val selectedOutline = MaterialTheme.colorScheme.primary
    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val transform = viewerOcrTransform(
            viewportWidth = widthPx,
            viewportHeight = heightPx,
            imageWidth = state.imageWidth,
            imageHeight = state.imageHeight,
            viewerTransform = ViewerTransformState(),
        ) ?: return@BoxWithConstraints
        val regionPolygons = remember(state.regions, transform) {
            state.regions.associateWith { region -> transformedOcrPolygon(region.polygon, transform) }
        }
        Canvas(modifier = Modifier.fillMaxSize()) {
            regionPolygons.forEach { (region, polygon) ->
                val path = polygon.toPath()
                drawPath(
                    path = path,
                    color = if (region.id == selectedId) selectedFill else fill,
                )
                drawPath(
                    path = path,
                    color = if (region.id == selectedId) selectedOutline else outline,
                    style = Stroke(width = 1.5.dp.toPx()),
                )
            }
        }
        regionPolygons.forEach { (region, polygon) ->
            val bounds = polygon.bounds()
            Box(
                modifier = Modifier
                    .offset { IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt()) }
                    .size(
                        width = with(density) { bounds.width.coerceAtLeast(1f).toDp() },
                        height = with(density) { bounds.height.coerceAtLeast(1f).toDp() },
                    )
                    .semantics {
                        contentDescription = "Translate detected ${region.language.displayLabel()} text"
                        onClick(label = "Translate text") {
                            onRegionActivated(region.id)
                            true
                        }
                    },
            )
        }
    }
}

@Composable
internal fun ViewerTranslationCardOverlay(
    state: ViewerOcrTranslationUiState,
    viewerTransform: ViewerTransformState,
    modifier: Modifier = Modifier,
) {
    val card = state.translationCard ?: return
    val region = state.regions.firstOrNull { candidate -> candidate.id == card.regionId } ?: return
    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val transform = viewerOcrTransform(
            viewportWidth = widthPx,
            viewportHeight = heightPx,
            imageWidth = state.imageWidth,
            imageHeight = state.imageHeight,
            viewerTransform = viewerTransform,
        ) ?: return@BoxWithConstraints
        val anchor = transformedOcrPolygon(region.polygon, transform).bounds()
        var measuredSize by remember(card.regionId, card::class) { mutableStateOf(IntSize.Zero) }
        val estimatedWidth = with(density) { DEFAULT_CARD_WIDTH.toPx() }
        val estimatedHeight = with(density) { DEFAULT_CARD_HEIGHT.toPx() }
        val cardWidth = measuredSize.width.takeIf { it > 0 }?.toFloat() ?: estimatedWidth
        val cardHeight = measuredSize.height.takeIf { it > 0 }?.toFloat() ?: estimatedHeight
        val placement = placeTranslationCard(
            anchorBounds = anchor,
            viewportWidth = widthPx,
            viewportHeight = heightPx,
            cardWidth = cardWidth,
            cardHeight = cardHeight,
            margin = with(density) { CARD_MARGIN.toPx() },
            gap = with(density) { CARD_GAP.toPx() },
        )
        Box(
            modifier = Modifier
                .offset { IntOffset(placement.x.roundToInt(), placement.y.roundToInt()) }
                .widthIn(min = 156.dp, max = DEFAULT_CARD_WIDTH)
                .heightIn(max = 160.dp)
                .onSizeChanged { measuredSize = it },
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                shadowElevation = 6.dp,
                tonalElevation = 2.dp,
                modifier = Modifier.semantics {
                    liveRegion = LiveRegionMode.Polite
                },
            ) {
                TranslationCardContent(card)
            }
        }
    }
}

internal fun viewerOcrTransform(
    viewportWidth: Float,
    viewportHeight: Float,
    imageWidth: Int,
    imageHeight: Int,
    viewerTransform: ViewerTransformState,
    contentPaddingPx: Float = 0f,
): ViewerOcrTransform? {
    val innerWidth = viewportWidth - contentPaddingPx * 2f
    val innerHeight = viewportHeight - contentPaddingPx * 2f
    val localFitted = fittedImageRect(innerWidth, innerHeight, imageWidth, imageHeight) ?: return null
    val fitted = ViewerOcrRect(
        left = localFitted.left + contentPaddingPx,
        top = localFitted.top + contentPaddingPx,
        right = localFitted.right + contentPaddingPx,
        bottom = localFitted.bottom + contentPaddingPx,
    )
    return ViewerOcrTransform(
        fittedImageRect = fitted,
        layerBounds = ViewerOcrRect(
            contentPaddingPx,
            contentPaddingPx,
            viewportWidth - contentPaddingPx,
            viewportHeight - contentPaddingPx,
        ),
        zoom = viewerTransform.zoom,
        panX = viewerTransform.panX,
        panY = viewerTransform.panY,
    )
}

@Composable
private fun TranslationCardContent(card: ViewerTranslationCardState) {
    when (card) {
        is ViewerTranslationCardState.PreparingTranslator -> LoadingTranslationCard("Preparing translation…")
        is ViewerTranslationCardState.Translating -> LoadingTranslationCard("Translating…")
        is ViewerTranslationCardState.Ready -> Text(
            text = card.translatedText,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 5,
            overflow = TextOverflow.Ellipsis,
        )
        is ViewerTranslationCardState.Failed -> Text(
            text = card.message,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LoadingTranslationCard(label: String) {
    Row(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

private fun ViewerOcrPolygon.toPath(): Path {
    val first = points.first()
    return Path().apply {
        moveTo(first.x, first.y)
        points.drop(1).forEach { point -> lineTo(point.x, point.y) }
        close()
    }
}

private fun ViewerOcrLanguage.displayLabel(): String = when (this) {
    ViewerOcrLanguage.JAPANESE -> "Japanese"
    ViewerOcrLanguage.CHINESE -> "Chinese"
    ViewerOcrLanguage.KOREAN -> "Korean"
}

private val DEFAULT_CARD_WIDTH = 300.dp
private val DEFAULT_CARD_HEIGHT = 64.dp
private val CARD_MARGIN = 8.dp
private val CARD_GAP = 8.dp
