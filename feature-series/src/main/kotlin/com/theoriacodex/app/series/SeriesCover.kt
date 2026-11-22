package com.theoriacodex.app.series

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.theoriacodex.app.media.MediaRequestFactory
import com.theoriacodex.domain.series.Series

/** A series' cover at book proportions; a plain tile while it loads or when the series has none. */
@Composable
internal fun SeriesCover(series: Series, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val request = remember(context, series.id, series.coverUrl) {
        series.coverUrl?.let { url ->
            MediaRequestFactory.imageRequest(context, url, series.id.source, crossfade = true, targetWidthPx = COVER_WIDTH_PX)
        }
    }
    Box(
        modifier
            .aspectRatio(COVER_ASPECT)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (request != null) AsyncImage(request, contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

/** A cover with the title under it; [unread] above zero shows as a badge on the cover. */
@Composable
internal fun SeriesCard(series: Series, onClick: () -> Unit, modifier: Modifier = Modifier, unread: Int = 0) {
    Column(modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)) {
        Box {
            SeriesCover(series, Modifier.fillMaxWidth())
            if (unread > 0) {
                Badge(Modifier.align(Alignment.TopEnd).padding(4.dp).semantics { contentDescription = "$unread unread" }) {
                    Text("$unread")
                }
            }
        }
        Text(
            series.title,
            Modifier.padding(horizontal = 2.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private const val COVER_ASPECT = 2f / 3f

/** Wide enough for the series page's cover on a dense phone; grid tiles scale it down. */
private const val COVER_WIDTH_PX = 480
