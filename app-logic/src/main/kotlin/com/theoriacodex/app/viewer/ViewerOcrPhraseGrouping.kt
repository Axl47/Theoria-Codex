package com.theoriacodex.app.viewer

import com.theoriacodex.data.repository.ViewerOcrLanguage
import kotlin.math.max
import kotlin.math.min

/**
 * Joins adjacent Japanese vertical columns into one phrase before translation.
 * The conservative geometry bounds favor leaving uncertain blocks separate over crossing bubbles.
 */
fun groupOcrPhraseRegions(regions: List<ViewerOcrRegion>): List<ViewerOcrRegion> {
    val indexed = regions.mapIndexed(::IndexedOcrRegion)
    val verticalJapanese = indexed.filter { indexedRegion ->
        indexedRegion.region.language == ViewerOcrLanguage.JAPANESE &&
            indexedRegion.region.polygon.bounds().isVerticalColumn()
    }
    if (verticalJapanese.isEmpty()) return regions

    val groups = mutableListOf<VerticalColumnGroup>()
    verticalJapanese
        .sortedWith(
            compareByDescending<IndexedOcrRegion> { it.bounds.center.x }
                .thenBy { it.bounds.top }
                .thenBy { it.index },
        )
        .forEach { candidate ->
            val destination = groups
                .filter { group -> group.canAppend(candidate) }
                .minByOrNull { group -> group.horizontalGapTo(candidate) }
            if (destination == null) {
                groups += VerticalColumnGroup(candidate)
            } else {
                destination.append(candidate)
            }
        }

    val verticalIndexes = verticalJapanese.mapTo(mutableSetOf(), IndexedOcrRegion::index)
    val output = indexed
        .filterNot { indexedRegion -> indexedRegion.index in verticalIndexes }
        .map { indexedRegion -> IndexedPhrase(indexedRegion.index, indexedRegion.region) }
        .toMutableList()
    groups.mapTo(output) { group -> IndexedPhrase(group.firstInputIndex, group.toRegion()) }
    return output.sortedBy(IndexedPhrase::index).map(IndexedPhrase::region)
}

private data class IndexedOcrRegion(
    val index: Int,
    val region: ViewerOcrRegion,
) {
    val bounds: ViewerOcrRect = region.polygon.bounds()
}

private data class IndexedPhrase(
    val index: Int,
    val region: ViewerOcrRegion,
)

private class VerticalColumnGroup(first: IndexedOcrRegion) {
    private val columns = mutableListOf(first)

    val firstInputIndex: Int get() = columns.minOf(IndexedOcrRegion::index)

    fun canAppend(candidate: IndexedOcrRegion): Boolean {
        val leftmost = columns.minBy { column -> column.bounds.center.x }
        if (candidate.bounds.center.x >= leftmost.bounds.center.x) return false
        if (!leftmost.bounds.hasCompatibleWidth(candidate.bounds)) return false
        if (leftmost.bounds.verticalOverlapRatio(candidate.bounds) < MIN_VERTICAL_OVERLAP_RATIO) {
            return false
        }
        if (horizontalGapTo(candidate) > leftmost.bounds.maximumColumnGap(candidate.bounds)) {
            return false
        }
        val union = (columns.map(IndexedOcrRegion::bounds) + candidate.bounds).union()
        return union.width <= union.height * MAX_GROUP_WIDTH_TO_HEIGHT_RATIO &&
            columns.size < MAX_COLUMNS_PER_PHRASE
    }

    fun horizontalGapTo(candidate: IndexedOcrRegion): Float {
        val leftmost = columns.minBy { column -> column.bounds.center.x }
        return max(0f, leftmost.bounds.left - candidate.bounds.right)
    }

    fun append(candidate: IndexedOcrRegion) {
        columns += candidate
    }

    fun toRegion(): ViewerOcrRegion {
        val ordered = columns.sortedByDescending { column -> column.bounds.center.x }
        if (ordered.size == 1) {
            val only = ordered.single().region
            return only.copy(sourceText = only.sourceText.withoutVerticalWhitespace())
        }
        val bounds = ordered.map(IndexedOcrRegion::bounds).union()
        val sourceText = ordered.joinToString(separator = "") { column ->
            column.region.sourceText.withoutVerticalWhitespace()
        }
        val memberIdentity = ordered.joinToString(separator = "|") { column -> column.region.id }
        return ViewerOcrRegion(
            id = "${ViewerOcrLanguage.JAPANESE.name}:group:${memberIdentity.hashCode()}",
            sourceText = sourceText,
            language = ViewerOcrLanguage.JAPANESE,
            polygon = bounds.toPolygon(),
        )
    }
}

private fun ViewerOcrRect.isVerticalColumn(): Boolean {
    return height >= width * MIN_VERTICAL_ASPECT_RATIO
}

private fun ViewerOcrRect.hasCompatibleWidth(other: ViewerOcrRect): Boolean {
    val smaller = min(width, other.width)
    if (smaller <= 0f) return false
    return max(width, other.width) / smaller <= MAX_COLUMN_WIDTH_RATIO
}

private fun ViewerOcrRect.verticalOverlapRatio(other: ViewerOcrRect): Float {
    val overlap = max(0f, min(bottom, other.bottom) - max(top, other.top))
    val smallerHeight = min(height, other.height)
    return if (smallerHeight > 0f) overlap / smallerHeight else 0f
}

private fun ViewerOcrRect.maximumColumnGap(other: ViewerOcrRect): Float {
    return max(MIN_COLUMN_GAP, max(width, other.width) * MAX_COLUMN_GAP_WIDTHS)
        .coerceAtMost(MAX_NORMALIZED_COLUMN_GAP)
}

private fun List<ViewerOcrRect>.union(): ViewerOcrRect = ViewerOcrRect(
    left = minOf(ViewerOcrRect::left),
    top = minOf(ViewerOcrRect::top),
    right = maxOf(ViewerOcrRect::right),
    bottom = maxOf(ViewerOcrRect::bottom),
)

private fun ViewerOcrRect.toPolygon(): ViewerOcrPolygon = ViewerOcrPolygon(
    listOf(
        ViewerOcrPoint(left, top),
        ViewerOcrPoint(right, top),
        ViewerOcrPoint(right, bottom),
        ViewerOcrPoint(left, bottom),
    ),
)

private fun String.withoutVerticalWhitespace(): String = filterNot(Char::isWhitespace)

private const val MIN_VERTICAL_ASPECT_RATIO = 1.4f
private const val MIN_VERTICAL_OVERLAP_RATIO = 0.3f
private const val MIN_COLUMN_GAP = 0.008f
private const val MAX_COLUMN_GAP_WIDTHS = 1.75f
private const val MAX_NORMALIZED_COLUMN_GAP = 0.055f
private const val MAX_COLUMN_WIDTH_RATIO = 2.25f
private const val MAX_GROUP_WIDTH_TO_HEIGHT_RATIO = 1.15f
private const val MAX_COLUMNS_PER_PHRASE = 6
