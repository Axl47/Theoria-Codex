package com.theoriacodex.app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.post.displayTitleOrNull
import com.theoriacodex.domain.model.Post

// Shelf previews share a square frame; titles retain their natural height below it.
internal const val POST_SHELF_MEDIA_ASPECT_RATIO = 1f

@Composable
internal fun rememberPostShelfHeight(sizingPosts: List<Post>, cardWidth: Dp): Dp {
    val titleHorizontalPadding = 20.dp
    val titleVerticalPadding = 16.dp
    val titleStyle = MaterialTheme.typography.bodyMedium
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    return remember(sizingPosts, cardWidth, titleStyle, density, textMeasurer) {
        val titleWidthPx = with(density) { (cardWidth - titleHorizontalPadding).roundToPx() }
        sizingPosts.maxOfOrNull { post ->
            val mediaHeight = cardWidth / POST_SHELF_MEDIA_ASPECT_RATIO
            val titleHeight = post.displayTitleOrNull()?.let { title ->
                val measuredHeightPx = textMeasurer.measure(
                    text = title,
                    style = titleStyle,
                    maxLines = 2,
                    constraints = Constraints(maxWidth = titleWidthPx),
                ).size.height
                with(density) { measuredHeightPx.toDp() } + titleVerticalPadding
            } ?: 0.dp
            mediaHeight + titleHeight
        } ?: 0.dp
    }
}
