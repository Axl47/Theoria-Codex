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
import androidx.compose.material3.Surface
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
import coil.request.ImageRequest
import com.theoriacodex.app.media.MediaRequestFactory
import com.theoriacodex.app.ui.theme.TextOverMediaShadow
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesCovers

/** What turns a stored cover into the address to load; the app provides its extension host's. */
val LocalSeriesCovers = staticCompositionLocalOf { SeriesCovers.Stored }

/** Builds the Coil request for [series]' cover; null when the series has none. */
@Composable
internal fun rememberSeriesCoverRequest(
    series: Series,
    targetWidthPx: Int = COVER_WIDTH_PX,
    targetHeightPx: Int = COVER_HEIGHT_PX,
): ImageRequest? {
    val context = LocalContext.current
    val covers = LocalSeriesCovers.current
    return remember(context, covers, series.id, series.coverUrl, targetWidthPx, targetHeightPx) {
        covers.address(series)?.let { url ->
            MediaRequestFactory.imageRequest(
                context,
                url,
                series.id.source,
                crossfade = true,
                targetWidthPx = targetWidthPx,
                targetHeightPx = targetHeightPx,
            )
        }
    }
}

/** A series' cover at book proportions; a plain tile while it loads or when the series has none. */
@Composable
internal fun SeriesCover(series: Series, modifier: Modifier = Modifier) {
    val request = rememberSeriesCoverRequest(series)
    Box(
        modifier
            .aspectRatio(COVER_ASPECT)
            .clip(COVER_SHAPE)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (request != null) AsyncImage(request, contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

/**
 * A cover card with the title overlaid at the bottom over a scrim; [unread] above zero shows as a badge at top right,
 * and [followed] marks series already in the user's Following list with a subtle pill at top left.
 */
@Composable
internal fun SeriesCard(
    series: Series,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    unread: Int = 0,
    followed: Boolean = false,
) {
    Box(
        modifier
            .aspectRatio(COVER_ASPECT)
            .clip(COVER_SHAPE)
            .clickable(onClick = onClick),
    ) {
        SeriesCover(series, Modifier.fillMaxSize())
        if (followed) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.22f)))
        Box(Modifier.fillMaxSize().background(TITLE_SCRIM))
        if (followed) {
            FollowingCardBadge(Modifier.align(Alignment.TopStart).padding(6.dp))
        }
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

@Composable
private fun FollowingCardBadge(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = FOLLOWING_BADGE_SHAPE,
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.92f),
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(
            text = "Following",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

private const val COVER_ASPECT = 2f / 3f
private val COVER_SHAPE = RoundedCornerShape(8.dp)
private val FOLLOWING_BADGE_SHAPE = RoundedCornerShape(6.dp)
private val TITLE_SCRIM = Brush.verticalGradient(
    0.45f to Color.Transparent,
    0.78f to Color.Black.copy(alpha = 0.55f),
    1f to Color.Black.copy(alpha = 0.85f),
)

/** Wide enough for the series page's cover on a dense phone; grid tiles scale it down. */
private const val COVER_WIDTH_PX = 480
private const val COVER_HEIGHT_PX = 720

/** High resolution target for the full-screen cover viewer. */
internal const val FULL_COVER_WIDTH_PX = 1600
internal const val FULL_COVER_HEIGHT_PX = 2400
