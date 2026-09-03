package com.theoriacodex.app.viewer

data class ViewerOcrCrop(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f) {
            "OCR crop bounds must be normalized"
        }
        require(right > left && bottom > top) { "OCR crop bounds must have positive area" }
    }
}

/** Overlapping quadrants make stylized manga text occupy more of ML Kit's input frame. */
fun japaneseOcrFallbackCrops(): List<ViewerOcrCrop> = JAPANESE_FALLBACK_CROPS

/** Removes duplicate text returned from overlapping crops without collapsing repeated effects. */
fun deduplicateOcrRegions(regions: List<ViewerOcrRegion>): List<ViewerOcrRegion> {
    val accepted = mutableListOf<ViewerOcrRegion>()
    regions.forEach { candidate ->
        val duplicate = accepted.indexOfFirst { existing ->
            existing.language == candidate.language &&
                existing.sourceText.normalizedOcrText() == candidate.sourceText.normalizedOcrText() &&
                existing.polygon.bounds().overlapOverSmaller(candidate.polygon.bounds()) >=
                MIN_DUPLICATE_OVERLAP
        }
        if (duplicate < 0) accepted += candidate
    }
    return accepted
}

private fun String.normalizedOcrText(): String = filterNot(Char::isWhitespace)

private fun ViewerOcrRect.overlapOverSmaller(other: ViewerOcrRect): Float {
    val overlapWidth = (minOf(right, other.right) - maxOf(left, other.left)).coerceAtLeast(0f)
    val overlapHeight = (minOf(bottom, other.bottom) - maxOf(top, other.top)).coerceAtLeast(0f)
    val smallerArea = minOf(area, other.area)
    return if (smallerArea > 0f) overlapWidth * overlapHeight / smallerArea else 0f
}

private val JAPANESE_FALLBACK_CROPS = listOf(
    ViewerOcrCrop(left = 0f, top = 0f, right = 0.58f, bottom = 0.58f),
    ViewerOcrCrop(left = 0.42f, top = 0f, right = 1f, bottom = 0.58f),
    ViewerOcrCrop(left = 0f, top = 0.42f, right = 0.58f, bottom = 1f),
    ViewerOcrCrop(left = 0.42f, top = 0.42f, right = 1f, bottom = 1f),
)

private const val MIN_DUPLICATE_OVERLAP = 0.5f
