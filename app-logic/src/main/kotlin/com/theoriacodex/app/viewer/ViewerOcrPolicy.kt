package com.theoriacodex.app.viewer

import com.theoriacodex.data.repository.ViewerOcrLanguage
import com.theoriacodex.domain.model.PostTaxonomyTerm
import com.theoriacodex.domain.model.SearchFacet
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class ViewerOcrPoint(
    val x: Float,
    val y: Float,
) {
    init {
        require(x.isFinite() && y.isFinite()) { "OCR point coordinates must be finite" }
    }
}

data class ViewerOcrPolygon(
    val points: List<ViewerOcrPoint>,
) {
    init {
        require(points.size >= MIN_POLYGON_POINTS) { "OCR polygons require at least three points" }
    }
}

data class ViewerOcrRegion(
    val id: String,
    val sourceText: String,
    val language: ViewerOcrLanguage,
    val polygon: ViewerOcrPolygon,
) {
    init {
        require(id.isNotBlank()) { "OCR region id must not be blank" }
        require(sourceText.isNotBlank()) { "OCR source text must not be blank" }
    }
}

data class ViewerOcrRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(listOf(left, top, right, bottom).all(Float::isFinite)) {
            "OCR rectangle coordinates must be finite"
        }
        require(right >= left && bottom >= top) { "OCR rectangle bounds are inverted" }
    }

    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val area: Float get() = width * height
    val center: ViewerOcrPoint get() = ViewerOcrPoint((left + right) / 2f, (top + bottom) / 2f)
}

data class ViewerOcrTransform(
    val fittedImageRect: ViewerOcrRect,
    val layerBounds: ViewerOcrRect,
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
) {
    init {
        require(zoom > 0f && zoom.isFinite()) { "OCR transform zoom must be positive and finite" }
        require(panX.isFinite() && panY.isFinite()) { "OCR transform pan must be finite" }
    }
}

fun metadataOcrLanguage(taxonomy: List<PostTaxonomyTerm>): ViewerOcrLanguage? {
    return taxonomy.asSequence()
        .filter { term -> term.facet == SearchFacet.LANGUAGE }
        .mapNotNull { term -> term.value.toViewerOcrLanguageOrNull() }
        .firstOrNull()
}

fun orderedOcrLanguages(
    metadataLanguage: ViewerOcrLanguage?,
    enabledLanguages: Set<ViewerOcrLanguage>,
    readyLanguages: Set<ViewerOcrLanguage>,
): List<ViewerOcrLanguage> {
    val eligible = enabledLanguages intersect readyLanguages
    if (eligible.isEmpty()) return emptyList()
    return buildList {
        metadataLanguage?.takeIf { it in eligible }?.let(::add)
        OCR_FALLBACK_ORDER.filterTo(this) { language -> language in eligible && language !in this }
    }
}

/** Recovery crops require stronger evidence when a lone CJK glyph appears inside an all-caps Latin word. */
fun containsScriptEvidence(
    text: String,
    language: ViewerOcrLanguage,
    fromFallbackCrop: Boolean = false,
): Boolean {
    var offset = 0
    var hasMatchingScript = false
    var cjkCount = 0
    while (offset < text.length) {
        val codePoint = Character.codePointAt(text, offset)
        val script = Character.UnicodeScript.of(codePoint)
        val matches = when (language) {
            ViewerOcrLanguage.JAPANESE -> script == Character.UnicodeScript.HIRAGANA ||
                script == Character.UnicodeScript.KATAKANA ||
                script == Character.UnicodeScript.HAN
            ViewerOcrLanguage.CHINESE -> script == Character.UnicodeScript.HAN
            ViewerOcrLanguage.KOREAN -> script == Character.UnicodeScript.HANGUL
        }
        if (matches && !fromFallbackCrop) return true
        hasMatchingScript = hasMatchingScript || matches
        if (script in CJK_SCRIPTS) cjkCount++
        offset += Character.charCount(codePoint)
    }
    return hasMatchingScript && (cjkCount > 1 || !hasLatinDominatedOcrToken(text))
}

private fun hasLatinDominatedOcrToken(text: String): Boolean {
    return OCR_WORDS.findAll(text).any { match ->
        var latinCount = 0
        var hasCjk = false
        var hasLowercaseLatin = false
        match.value.codePoints().forEach { codePoint ->
            val script = Character.UnicodeScript.of(codePoint)
            hasCjk = hasCjk || script in CJK_SCRIPTS
            if (script == Character.UnicodeScript.LATIN) {
                latinCount++
                hasLowercaseLatin = hasLowercaseLatin || !Character.isUpperCase(codePoint)
            }
        }
        hasCjk && latinCount >= MIN_NOISY_LATIN_LETTERS && !hasLowercaseLatin
    }
}

fun normalizeOcrPolygon(
    points: List<ViewerOcrPoint>,
    bitmapWidth: Int,
    bitmapHeight: Int,
): ViewerOcrPolygon? {
    if (bitmapWidth <= 0 || bitmapHeight <= 0 || points.size < MIN_POLYGON_POINTS) return null
    val normalized = points.map { point ->
        ViewerOcrPoint(
            x = (point.x / bitmapWidth).coerceIn(0f, 1f),
            y = (point.y / bitmapHeight).coerceIn(0f, 1f),
        )
    }
    val bounds = normalized.bounds()
    if (bounds.width <= MIN_NORMALIZED_EXTENT || bounds.height <= MIN_NORMALIZED_EXTENT) return null
    return ViewerOcrPolygon(normalized)
}

fun fittedImageRect(
    containerWidth: Float,
    containerHeight: Float,
    imageWidth: Int,
    imageHeight: Int,
): ViewerOcrRect? {
    if (
        containerWidth <= 0f || containerHeight <= 0f ||
        imageWidth <= 0 || imageHeight <= 0
    ) {
        return null
    }
    val scale = min(containerWidth / imageWidth, containerHeight / imageHeight)
    val width = imageWidth * scale
    val height = imageHeight * scale
    val left = (containerWidth - width) / 2f
    val top = (containerHeight - height) / 2f
    return ViewerOcrRect(left, top, left + width, top + height)
}

fun transformedOcrPolygon(
    polygon: ViewerOcrPolygon,
    transform: ViewerOcrTransform,
): ViewerOcrPolygon {
    val layerCenter = transform.layerBounds.center
    return ViewerOcrPolygon(
        polygon.points.map { normalized ->
            val fittedX = transform.fittedImageRect.left + normalized.x * transform.fittedImageRect.width
            val fittedY = transform.fittedImageRect.top + normalized.y * transform.fittedImageRect.height
            ViewerOcrPoint(
                x = layerCenter.x + (fittedX - layerCenter.x) * transform.zoom + transform.panX,
                y = layerCenter.y + (fittedY - layerCenter.y) * transform.zoom + transform.panY,
            )
        },
    )
}

fun selectOcrRegionAtTap(
    regions: List<ViewerOcrRegion>,
    transform: ViewerOcrTransform,
    tap: ViewerOcrPoint,
    hitExpansionPx: Float,
): ViewerOcrRegion? {
    require(hitExpansionPx >= 0f && hitExpansionPx.isFinite()) {
        "OCR hit expansion must be non-negative and finite"
    }
    return regions.asSequence()
        .map { region -> region to transformedOcrPolygon(region.polygon, transform).bounds() }
        .filter { (_, bounds) -> bounds.expanded(hitExpansionPx).contains(tap) }
        .minWithOrNull(
            compareBy<Pair<ViewerOcrRegion, ViewerOcrRect>> { (_, bounds) -> bounds.area }
                .thenBy { (_, bounds) -> bounds.center.distanceSquaredTo(tap) }
                .thenBy { (region, _) -> region.id },
        )
        ?.first
}

fun placeTranslationCard(
    anchorBounds: ViewerOcrRect,
    viewportWidth: Float,
    viewportHeight: Float,
    cardWidth: Float,
    cardHeight: Float,
    margin: Float,
    gap: Float,
): ViewerOcrPoint {
    require(listOf(viewportWidth, viewportHeight, cardWidth, cardHeight, margin, gap).all(Float::isFinite)) {
        "Translation card geometry must be finite"
    }
    val maximumX = max(margin, viewportWidth - cardWidth - margin)
    val x = (anchorBounds.center.x - cardWidth / 2f).coerceIn(margin, maximumX)
    val below = anchorBounds.bottom + gap
    val above = anchorBounds.top - cardHeight - gap
    val maximumY = max(margin, viewportHeight - cardHeight - margin)
    val preferredY = if (below + cardHeight <= viewportHeight - margin) below else above
    return ViewerOcrPoint(x, preferredY.coerceIn(margin, maximumY))
}

fun ViewerOcrPolygon.bounds(): ViewerOcrRect = points.bounds()

private fun List<ViewerOcrPoint>.bounds(): ViewerOcrRect {
    return ViewerOcrRect(
        left = minOf(ViewerOcrPoint::x),
        top = minOf(ViewerOcrPoint::y),
        right = maxOf(ViewerOcrPoint::x),
        bottom = maxOf(ViewerOcrPoint::y),
    )
}

private fun ViewerOcrRect.expanded(amount: Float): ViewerOcrRect {
    return ViewerOcrRect(left - amount, top - amount, right + amount, bottom + amount)
}

private fun ViewerOcrRect.contains(point: ViewerOcrPoint): Boolean {
    return point.x in left..right && point.y in top..bottom
}

private fun ViewerOcrPoint.distanceSquaredTo(other: ViewerOcrPoint): Float {
    val dx = x - other.x
    val dy = y - other.y
    return dx * dx + dy * dy
}

private fun String.toViewerOcrLanguageOrNull(): ViewerOcrLanguage? {
    val normalized = trim().lowercase(Locale.ROOT).replace('_', '-')
    return when (normalized) {
        "japanese", "ja", "ja-jp" -> ViewerOcrLanguage.JAPANESE
        "chinese", "zh", "zh-cn", "zh-tw", "zh-hans", "zh-hant" -> ViewerOcrLanguage.CHINESE
        "korean", "ko", "ko-kr" -> ViewerOcrLanguage.KOREAN
        else -> null
    }
}

private val OCR_FALLBACK_ORDER = listOf(
    ViewerOcrLanguage.JAPANESE,
    ViewerOcrLanguage.CHINESE,
    ViewerOcrLanguage.KOREAN,
)
private const val MIN_POLYGON_POINTS = 3
private const val MIN_NORMALIZED_EXTENT = 0.0001f

// Keep standalone CJK, normal acronyms (USB/HDMI), mixed-case brands and multi-glyph phrases.
// Cropped Latin watermarks can instead turn one letter into a Han/Kana glyph, e.g. 三RMARK.
private const val MIN_NOISY_LATIN_LETTERS = 5
private val OCR_WORDS = Regex("[\\p{L}\\p{N}]+")
private val CJK_SCRIPTS = setOf(
    Character.UnicodeScript.HAN,
    Character.UnicodeScript.HIRAGANA,
    Character.UnicodeScript.KATAKANA,
    Character.UnicodeScript.HANGUL,
)
