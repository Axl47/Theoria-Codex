package com.theoriacodex.app.series

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.theoriacodex.app.media.MediaRequestFactory
import com.theoriacodex.app.ui.theme.TextOverMediaShadow
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesSource

/** The sources that are on, for the covers drawn below: each cover loads through its series' own source. */
internal val LocalSeriesSources = staticCompositionLocalOf<List<SeriesSource>> { emptyList() }

/**
 * A series' cover at book proportions; a plain tile while it loads or when the series has none. A series whose source
 * is not among [LocalSeriesSources] loads the stored address as it is.
 */
@Composable
internal fun SeriesCover(series: Series, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val source = LocalSeriesSources.current.firstOrNull { it.key == series.id.source }
    val request = remember(context, series.id, series.coverUrl, source) {
        (if (source != null) source.coverAddress(series) else series.coverUrl)?.let { url ->
            MediaRequestFactory.imageRequest(context, url, series.id.source, crossfade = true, targetWidthPx = COVER_WIDTH_PX)
        }
    }
    Box(
        modifier
            .aspectRatio(COVER_ASPECT)
            .clip(COVER_SHAPE)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (request != null) AsyncImage(request, contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

/** A cover card with the title overlaid at the bottom over a scrim; [unread] above zero shows as a badge at top right. */
@Composable
internal fun SeriesCard(series: Series, onClick: () -> Unit, modifier: Modifier = Modifier, unread: Int = 0) {
    Box(
        modifier
            .aspectRatio(COVER_ASPECT)
            .clip(COVER_SHAPE)
            .clickable(onClick = onClick),
    ) {
        SeriesCover(series, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(TITLE_SCRIM))
        if (unread > 0) {
            Badge(Modifier.align(Alignment.TopEnd).padding(6.dp).semantics { contentDescription = "$unread unread" }) {
                Text("$unread")
            }
        }
        Text(
            text = series.title,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium.copy(
                color = Color.White,
                shadow = TextOverMediaShadow,
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private const val COVER_ASPECT = 2f / 3f
private val COVER_SHAPE = RoundedCornerShape(8.dp)
private val TITLE_SCRIM = Brush.verticalGradient(
    0.45f to Color.Transparent,
    0.78f to Color.Black.copy(alpha = 0.55f),
    1f to Color.Black.copy(alpha = 0.85f),
)

/** Wide enough for the series page's cover on a dense phone; grid tiles scale it down. */
private const val COVER_WIDTH_PX = 480
