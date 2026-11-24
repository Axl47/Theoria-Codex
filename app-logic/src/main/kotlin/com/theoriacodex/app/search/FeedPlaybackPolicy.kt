package com.theoriacodex.app.search

data class FeedPreviewDecodeSize(
    val widthPx: Int,
    val heightPx: Int,
)

fun feedPreviewDecodeSize(
    screenWidthPx: Int,
    aspectRatio: Float,
): FeedPreviewDecodeSize {
    val safeRatio = aspectRatio.takeIf { it.isFinite() && it > 0f } ?: 1f
    val width = (screenWidthPx.coerceAtLeast(2) / 2)
        .coerceAtMost(FEED_PREVIEW_MAX_WIDTH_PX)
        .coerceAtLeast(1)
    val height = (width / safeRatio).toInt().coerceIn(1, FEED_PREVIEW_MAX_HEIGHT_PX)
    return FeedPreviewDecodeSize(width, height)
}

const val FEED_PLAYER_ACTIVATION_DELAY_MS = 180L

const val FEED_PLAYER_RELEASE_GRACE_MS = 1_000L

private const val FEED_PREVIEW_MAX_WIDTH_PX = 1_600
private const val FEED_PREVIEW_MAX_HEIGHT_PX = 2_400
