package com.theoriacodex.app.media

enum class MediaStorageCategory {
    OFFLINE,
    IMAGES,
    VIDEO,
    GIF,
    ANIMATION,
    DOWNLOADS,
}

data class MediaStorageUsage(val category: MediaStorageCategory, val bytes: Long)

/** Categories are disjoint stores (downloads live in the public Downloads folder); percentages share the complete managed-media total. */
data class MediaStorageBreakdown(val categories: List<MediaStorageUsage>) {
    val totalBytes: Long = categories.sumOf(MediaStorageUsage::bytes)

    fun percentage(usage: MediaStorageUsage): Double =
        if (totalBytes == 0L) 0.0 else usage.bytes.toDouble() / totalBytes * 100.0
}

fun mediaStorageBreakdown(
    offlineBytes: Long,
    imageBytes: Long,
    videoBytes: Long,
    gifBytes: Long,
    animationBytes: Long,
    downloadBytes: Long,
): MediaStorageBreakdown = MediaStorageBreakdown(
    listOf(
        MediaStorageUsage(MediaStorageCategory.OFFLINE, offlineBytes.coerceAtLeast(0)),
        MediaStorageUsage(MediaStorageCategory.IMAGES, imageBytes.coerceAtLeast(0)),
        MediaStorageUsage(MediaStorageCategory.VIDEO, videoBytes.coerceAtLeast(0)),
        MediaStorageUsage(MediaStorageCategory.GIF, gifBytes.coerceAtLeast(0)),
        MediaStorageUsage(MediaStorageCategory.ANIMATION, animationBytes.coerceAtLeast(0)),
        MediaStorageUsage(MediaStorageCategory.DOWNLOADS, downloadBytes.coerceAtLeast(0)),
    ),
)
