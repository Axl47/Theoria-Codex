package com.theoriacodex.sources.hitomi

import java.net.URI
import java.net.URLEncoder
import java.util.Locale

/** Hitomi's file hosts and data host (`ltn.`) are all subdomains of this one domain. */
internal const val HITOMI_CDN_DOMAIN = "gold-usergeneratedcontent.net"

/** A file hash as Hitomi names it, once lowercased. */
internal val HITOMI_FILE_HASH = Regex("[0-9a-f]{64}")

/** A file extension safe to put in a media URL. */
internal val HITOMI_FILE_EXTENSION = Regex("[A-Za-z0-9]{1,10}")

/**
 * One full-size media location: `https://{a|w|}{shard}.<domain>/[images/]{version}/{pathNumber}/{hash}.{extension}`,
 * where AVIF hosts start with `a`, WebP hosts with `w`, and originals add the `images/` prefix. [version] is the
 * `gg.js` base path without its trailing slash, so recovery can tell which configuration produced a failed URL.
 */
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

        /** Reads a URL [url] produced back into its parts; null for thumbnails and anything not a media location. */
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

/** The two thumbnail sizes Hitomi's own pages use; [directory] names the rendition on its thumbnail CDN. */
internal enum class HitomiThumbnail(val directory: String) {
    /** 640 px on the long edge, for feed cards. */
    CARD("webpbigtn"),

    /** 300 px on the long edge, for choosing a page. */
    PAGE("webpsmalltn"),
}

/**
 * URLs of [rendition] for the file with [hash], the configured shard first. Thumbnail paths are derived from the
 * hash alone, so they survive the dated `gg.js` base path changing, unlike full-size media.
 */
internal fun hitomiThumbnailUrls(hash: String, primaryShard: Int, rendition: HitomiThumbnail): List<String> {
    val normalized = hash.lowercase(Locale.ROOT)
    val path = "${rendition.directory}/${normalized.last()}/${normalized.takeLast(3).take(2)}/$normalized.webp"
    return listOf(primaryShard, 3 - primaryShard).map { shard ->
        "https://${'a' + (shard - 1)}tn.$HITOMI_CDN_DOMAIN/$path"
    }
}

private val HITOMI_THUMBNAIL_URL = Regex("""^https://[ab]tn\.${Regex.escape(HITOMI_CDN_DOMAIN)}/""")

/** Whether [url] is on a thumbnail host, as [hitomiThumbnailUrls] builds them. */
internal fun isHitomiThumbnailUrl(url: String): Boolean = HITOMI_THUMBNAIL_URL.containsMatchIn(url)

/** The streaming URL of a gallery video file. */
internal fun hitomiVideoUrl(filename: String): String {
    val encoded = URLEncoder.encode(filename, Charsets.UTF_8.name()).replace("+", "%20")
    return "https://streaming.$HITOMI_CDN_DOMAIN/videos/$encoded"
}
