package com.theoriacodex.sources.hitomi

import java.net.URI
import java.util.Locale
import com.theoriacodex.sources.common.percentEncode

internal const val HITOMI_CDN_DOMAIN = "gold-usergeneratedcontent.net"

internal val HITOMI_FILE_HASH = Regex("[0-9a-f]{64}")

internal val HITOMI_FILE_EXTENSION = Regex("[A-Za-z0-9]{1,10}")

internal data class HitomiMediaLocation(
    val format: HitomiMediaFormat,
    val shard: Int,
    val version: String,
    val pathNumber: Int,
    val hash: String,
    val extension: String,
) {
    val url: String
        get() {
            val hostPrefix = when (format) {
                HitomiMediaFormat.AVIF -> "a"
                HitomiMediaFormat.WEBP -> "w"
                HitomiMediaFormat.ORIGINAL -> ""
            }
            val pathPrefix = if (format == HitomiMediaFormat.ORIGINAL) "images/" else ""
            return "https://$hostPrefix$shard.$HITOMI_CDN_DOMAIN/$pathPrefix$version/$pathNumber/$hash.$extension"
        }

    companion object {
        private val MEDIA_HOST = Regex("""([aw]?)([12])\.${Regex.escape(HITOMI_CDN_DOMAIN)}""")

        fun parse(url: String): HitomiMediaLocation? {
            val parsed = runCatching { URI(url) }.getOrNull() ?: return null
            val host = MEDIA_HOST.matchEntire(parsed.host?.lowercase(Locale.ROOT) ?: return null) ?: return null
            val format = when (host.groupValues[1]) {
                "a" -> HitomiMediaFormat.AVIF
                "w" -> HitomiMediaFormat.WEBP
                else -> HitomiMediaFormat.ORIGINAL
            }
            val segments = parsed.path?.split('/')?.filter(String::isNotBlank).orEmpty()
            val baseStart = if (format == HitomiMediaFormat.ORIGINAL) {
                if (segments.firstOrNull() != "images") return null
                1
            } else {
                0
            }
            if (segments.size - baseStart < 3) return null
            val fileName = segments.last()
            val hash = fileName.substringBeforeLast('.', missingDelimiterValue = "").lowercase(Locale.ROOT)
            val extension = fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.ROOT)
            if (!HITOMI_FILE_HASH.matches(hash) || !HITOMI_FILE_EXTENSION.matches(extension)) return null
            val pathNumber = segments[segments.lastIndex - 1].toIntOrNull() ?: return null
            val baseSegments = segments.subList(baseStart, segments.lastIndex - 1)
            if (baseSegments.isEmpty()) return null
            return HitomiMediaLocation(
                format = format,
                shard = host.groupValues[2].toInt(),
                version = baseSegments.joinToString("/"),
                pathNumber = pathNumber,
                hash = hash,
                extension = extension,
            )
        }
    }
}

internal enum class HitomiThumbnail(val directory: String) {
    CARD("webpbigtn"),

    PAGE("webpsmalltn"),
}

internal fun hitomiThumbnailUrls(hash: String, primaryShard: Int, rendition: HitomiThumbnail): List<String> {
    val normalized = hash.lowercase(Locale.ROOT)
    val path = "${rendition.directory}/${normalized.last()}/${normalized.takeLast(3).take(2)}/$normalized.webp"
    return listOf(primaryShard, 3 - primaryShard).map { shard ->
        "https://${'a' + (shard - 1)}tn.$HITOMI_CDN_DOMAIN/$path"
    }
}

private val HITOMI_THUMBNAIL_URL = Regex("""^https://[ab]tn\.${Regex.escape(HITOMI_CDN_DOMAIN)}/""")

internal fun isHitomiThumbnailUrl(url: String): Boolean = HITOMI_THUMBNAIL_URL.containsMatchIn(url)

internal fun hitomiVideoUrl(filename: String): String {
    return "https://streaming.$HITOMI_CDN_DOMAIN/videos/${filename.percentEncode()}"
}
