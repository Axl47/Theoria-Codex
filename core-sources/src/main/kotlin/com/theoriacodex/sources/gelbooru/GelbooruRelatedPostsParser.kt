package com.theoriacodex.sources.gelbooru

import org.jsoup.Jsoup

/** Returns null when Gelbooru's related block is absent, versus an empty present block. */
internal fun parseGelbooruRelatedPostIds(
    html: String,
    seedId: String,
    limit: Int,
): List<String>? {
    if (limit <= 0) return emptyList()
    val document = Jsoup.parse(html, GELBOORU_BASE_URL)
    val heading = document.getElementsContainingOwnText(RELATED_HEADING)
        .firstOrNull { element -> element.ownText().trim().startsWith(RELATED_HEADING) }
        ?: return null
    val container = heading.parent() ?: return emptyList()
    return container.select("a[href]")
        .asSequence()
        .mapNotNull { link -> relatedPostId(link.attr("href")) }
        .filterNot { id -> id == seedId }
        .distinct()
        .take(limit.coerceAtMost(MAX_RELATED_POSTS))
        .toList()
}

private fun relatedPostId(href: String): String? {
    if (!PAGE_POST_PATTERN.containsMatchIn(href) || !VIEW_POST_PATTERN.containsMatchIn(href)) return null
    return POST_ID_PATTERN.find(href)?.groupValues?.getOrNull(1)
}

private const val RELATED_HEADING = "More Like This:"
private const val GELBOORU_BASE_URL = "https://gelbooru.com/"
private const val MAX_RELATED_POSTS = 6
private val PAGE_POST_PATTERN = Regex("(?:[?&])page=post(?:[&#]|$)")
private val VIEW_POST_PATTERN = Regex("(?:[?&])s=view(?:[&#]|$)")
private val POST_ID_PATTERN = Regex("(?:[?&])id=(\\d+)(?:[&#]|$)")
