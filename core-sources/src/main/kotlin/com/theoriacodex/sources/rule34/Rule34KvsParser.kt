package com.theoriacodex.sources.rule34

import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import org.jsoup.nodes.Document

internal data class Rule34KvsConfig(
    val values: Map<String, String>,
) {
    fun string(key: String): String? = values[key]?.trim()?.takeIf { it.isNotEmpty() }
}

internal fun parseRule34KvsConfig(document: Document): Rule34KvsConfig {
    val scriptBody = document.select("script")
        .mapNotNull { script -> script.data().takeIf { it.contains("video_url") && it.contains("kt_player") } }
        .firstOrNull()
        ?: throw SourceAdapterException(
            reason = SourceFailureReason.PARSE,
            message = "Rule34 video page missing player config",
        )

    val objectBody = RULE34_KVS_OBJECT_REGEX.find(scriptBody)?.groupValues?.getOrNull(1)
        ?: throw SourceAdapterException(
            reason = SourceFailureReason.PARSE,
            message = "Rule34 video page player config malformed",
        )

    val values = buildMap {
        RULE34_KVS_PAIR_REGEX.findAll(objectBody).forEach { match ->
            val key = match.groupValues[1].trim()
            val rawValue = match.groupValues[2]
            put(key, unescapeJsString(rawValue))
        }
    }
    if (values.isEmpty()) {
        throw SourceAdapterException(
            reason = SourceFailureReason.PARSE,
            message = "Rule34 video page contained no player fields",
        )
    }
    return Rule34KvsConfig(values)
}

internal fun Rule34KvsConfig.bestVideoUrl(): String? {
    val candidates = values.entries
        .filter { (key, value) ->
            value.isNotBlank() && (key == "video_url" || RULE34_ALT_VIDEO_KEY_REGEX.matches(key))
        }
        .map { (key, url) ->
            VideoCandidate(
                url = url,
                score = inferVideoQualityScore(
                    url = url,
                    label = values["${key}_text"],
                )
            )
        }
    return candidates.maxByOrNull(VideoCandidate::score)?.url
}

internal fun Rule34KvsConfig.previewImageUrl(): String? {
    return sequenceOf(
        string("preview_url4"),
        string("preview_url3"),
        string("preview_url2"),
        string("preview_url1"),
        string("preview_url"),
    ).filterNotNull().firstOrNull()
}

internal fun Rule34KvsConfig.tags(): List<String> {
    val groups = listOf(
        string("video_tags"),
        string("video_categories"),
        string("video_models"),
    )
    return groups
        .flatMap { raw ->
            raw.orEmpty()
                .split(',')
                .map(String::trim)
                .filter(String::isNotBlank)
        }
        .distinctBy(String::lowercase)
}

private fun inferVideoQualityScore(url: String, label: String?): Int {
    val fromLabel = RULE34_QUALITY_REGEX.find(label.orEmpty())
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
    if (fromLabel != null) return fromLabel

    return RULE34_QUALITY_REGEX.find(url)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
        ?: if ("_hd" in url.lowercase()) 720 else 0
}

private fun unescapeJsString(value: String): String {
    return value
        .replace("\\\\", "\\")
        .replace("\\'", "'")
        .replace("\\\"", "\"")
        .replace("\\/", "/")
        .replace("\\n", "\n")
        .replace("\\r", "\r")
        .replace("\\t", "\t")
}

private data class VideoCandidate(
    val url: String,
    val score: Int,
)

private val RULE34_KVS_OBJECT_REGEX =
    Regex("""=\s*\{(.*?video_url.*?)}\s*;\s*(?:window\[['"]player_obj['"]]|kt_player\()""", setOf(RegexOption.DOT_MATCHES_ALL))
private val RULE34_KVS_PAIR_REGEX = Regex("""([A-Za-z0-9_]+)\s*:\s*'((?:\\.|[^'])*)'""")
private val RULE34_ALT_VIDEO_KEY_REGEX = Regex("""video_alt_url\d*""")
private val RULE34_QUALITY_REGEX = Regex("""(\d{3,4})p?""")
