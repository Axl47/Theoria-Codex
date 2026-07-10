package com.theoriacodex.sources.hitomi

import com.theoriacodex.sources.http.SourceByteRange
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

enum class HitomiNozomiSort(
    internal val orderBy: String,
    internal val orderKey: String,
) {
    NEWEST(orderBy = "date", orderKey = "added"),
    PUBLISHED(orderBy = "date", orderKey = "published"),
    POPULAR_TODAY(orderBy = "popular", orderKey = "today"),
    POPULAR_WEEK(orderBy = "popular", orderKey = "week"),
    POPULAR_MONTH(orderBy = "popular", orderKey = "month"),
    POPULAR_YEAR(orderBy = "popular", orderKey = "year"),
}

data class HitomiNozomiRequest(
    val area: String = "all",
    val tag: String = "index",
    val language: String = "all",
    val sort: HitomiNozomiSort = HitomiNozomiSort.NEWEST,
)

object HitomiNozomi {
    const val MAX_GALLERY_IDS: Int = 2_000_000

    private val areas = setOf("all", "tag", "artist", "character", "series", "group", "type")

    fun urlFor(request: HitomiNozomiRequest): String {
        val area = normalizeArea(request.area)
        val tag = encodePathValue(request.tag, "tag")
        val language = encodePathValue(request.language, "language")
        val path = when {
            request.sort == HitomiNozomiSort.NEWEST && area == "all" -> "$tag-$language"
            request.sort == HitomiNozomiSort.NEWEST -> "$area/$tag-$language"
            area == "all" -> "${request.sort.orderBy}/${request.sort.orderKey}-$language"
            else -> "$area/${request.sort.orderBy}/${request.sort.orderKey}/$tag-$language"
        }
        return "${HitomiProtocol.DATA_BASE_URL}/n/$path.nozomi"
    }

    fun byteRangeForIds(firstIdIndex: Long, idCount: Int): SourceByteRange {
        if (firstIdIndex < 0L) {
            throw HitomiProtocolException("Nozomi ID offset must be non-negative")
        }
        if (idCount <= 0) {
            throw HitomiProtocolException("Nozomi range must request at least one ID")
        }
        val start = runCatching { Math.multiplyExact(firstIdIndex, Int.SIZE_BYTES.toLong()) }
            .getOrElse { throw HitomiProtocolException("Nozomi range start overflowed") }
        val byteCount = runCatching { Math.multiplyExact(idCount.toLong(), Int.SIZE_BYTES.toLong()) }
            .getOrElse { throw HitomiProtocolException("Nozomi range length overflowed") }
        val end = runCatching { Math.addExact(start, byteCount - 1L) }
            .getOrElse { throw HitomiProtocolException("Nozomi range end overflowed") }
        return SourceByteRange(startInclusive = start, endInclusive = end)
    }

    fun decodeGalleryIds(bytes: ByteArray, maxGalleryIds: Int = MAX_GALLERY_IDS): List<Int> {
        if (maxGalleryIds <= 0) {
            throw HitomiProtocolException("Nozomi gallery ID limit must be positive")
        }
        if (bytes.size % Int.SIZE_BYTES != 0) {
            throw HitomiProtocolException("Nozomi response was truncated")
        }
        val count = bytes.size / Int.SIZE_BYTES
        if (count > maxGalleryIds) {
            throw HitomiProtocolException("Nozomi response exceeded the gallery ID limit")
        }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        return List(count) { index ->
            val id = buffer.int
            if (id <= 0) {
                throw HitomiProtocolException("Nozomi gallery ID at index $index was not positive")
            }
            id
        }
    }

    private fun normalizeArea(value: String): String {
        val normalized = value.trim().lowercase(Locale.ROOT)
        if (normalized !in areas) {
            throw HitomiProtocolException("Unsupported Hitomi Nozomi area: $value")
        }
        return normalized
    }

    private fun encodePathValue(value: String, label: String): String {
        val normalized = value.trim().lowercase(Locale.ROOT)
        if (normalized.isBlank() || normalized.length > 256) {
            throw HitomiProtocolException("Hitomi Nozomi $label must contain 1..256 characters")
        }
        return URLEncoder.encode(normalized, Charsets.UTF_8.name()).replace("+", "%20")
    }
}
