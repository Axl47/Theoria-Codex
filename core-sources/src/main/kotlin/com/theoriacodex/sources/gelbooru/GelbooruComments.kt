package com.theoriacodex.sources.gelbooru

import com.google.gson.JsonObject
import com.theoriacodex.domain.model.CommentHint
import com.theoriacodex.domain.model.PostComment
import com.theoriacodex.sources.common.booleanValue
import com.theoriacodex.sources.common.htmlFragmentToText
import com.theoriacodex.sources.common.intValue
import com.theoriacodex.sources.common.parseBooruDateTimeMs
import com.theoriacodex.sources.common.parseHtml
import com.theoriacodex.sources.common.parseXml
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

internal data class GelbooruHtmlComments(val comments: List<PostComment>, val nextPid: Int?)

internal fun parseGelbooruCommentsXml(body: String, site: GelbooruSite): List<PostComment>? {
    val root = parseXml(body).selectFirst("comments") ?: return null
    return root.select("comment").mapNotNull { element ->
        val id = element.attr("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
        PostComment(
            id = id,
            authorName = element.attr("creator").trim(),
            authorAvatarUrl = null,
            body = element.attr("body").trim(),
            createdAtEpochMs = parseBooruDateTimeMs(element.attr("created_at")),
            author = site.creatorProfile(element.attr("creator"), element.attr("creator_id")),
        )
    }
}

internal fun parseGelbooruCommentsHtml(html: String, site: GelbooruSite, currentPid: Int): GelbooruHtmlComments {
    val document = parseHtml(html, "${site.siteUrl}/")
    val bodies = document.select("div.commentBody")
    val comments = if (bodies.isNotEmpty()) {
        bodies.mapNotNull { parseCommentBody(it, site) }
    } else {
        document.select("div[id~=^c\\d+$]").mapNotNull { parseColumnComment(it, site) }
    }
    return GelbooruHtmlComments(comments, nextPaginatorPid(document, currentPid))
}

internal fun nextPaginatorPid(document: Document, currentPid: Int): Int? =
    document.select("#paginator a[href], .paginator a[href]")
        .mapNotNull { link -> PID_REGEX.find(link.attr("href"))?.groupValues?.get(1)?.toIntOrNull() }
        .filter { it > currentPid }
        .minOrNull()

private fun parseCommentBody(body: Element, site: GelbooruSite): PostComment? {
    val authorLink = body.selectFirst("a")
    val author = body.selectFirst("a > b")?.text() ?: authorLink?.text()
    val copy = body.clone()
    copy.select("span, script").remove()
    val nodes = copy.childNodes()
    val header = nodes.takeWhile { !it.isBr() }.joinToString(" ") { node ->
        when (node) {
            is Element -> node.text()
            is TextNode -> node.text()
            else -> ""
        }
    }
    val id = COMMENT_ID_REGEX.find(header)?.groupValues?.get(1)
        ?: body.selectFirst("span[id~=^sc\\d+$]")?.id()?.removePrefix("sc")
        ?: return null
    val avatar = body.previousElementSibling()?.takeIf { it.hasClass("commentAvatar") }?.let { avatarUrl(it, site.siteUrl) }
    return PostComment(
        id = id,
        authorName = author?.trim().orEmpty(),
        authorAvatarUrl = avatar,
        body = textAfterBlankLine(nodes),
        createdAtEpochMs = parseBooruDateTimeMs(DATE_REGEX.find(header)?.value),
        author = site.creatorProfile(author, authorLink?.let(::profileId)),
    )
}

private fun textAfterBlankLine(nodes: List<Node>): String {
    val firstBreak = nodes.indexOfFirst { it.isBr() }
    if (firstBreak < 0) return ""
    val secondBreak = (firstBreak + 1 until nodes.size).firstOrNull { index ->
        val node = nodes[index]
        node !is TextNode || !node.isBlank
    }?.takeIf { nodes[it].isBr() } ?: return ""
    return htmlFragmentToText(nodes.drop(secondBreak + 1).joinToString("") { it.outerHtml() })
}

private fun parseColumnComment(comment: Element, site: GelbooruSite): PostComment? {
    val info = comment.selectFirst("div.col1") ?: return null
    val text = comment.selectFirst("div.col2") ?: return null
    val authorLink = info.selectFirst("a")
    return PostComment(
        id = comment.id().removePrefix("c"),
        authorName = authorLink?.text()?.trim().orEmpty(),
        authorAvatarUrl = null,
        body = htmlFragmentToText(text.html()),
        createdAtEpochMs = parseBooruDateTimeMs(DATE_REGEX.find(info.text())?.value),
        author = site.creatorProfile(authorLink?.text(), authorLink?.let(::profileId)),
    )
}

private fun profileId(link: Element): String? = PROFILE_ID_REGEX.find(link.attr("href"))?.groupValues?.get(1)

private fun Node.isBr(): Boolean = this is Element && tagName() == "br"

internal fun avatarUrl(avatar: Element, siteUrl: String): String? {
    val raw = avatar.selectFirst("img[src]")?.attr("src")
        ?: avatar.select("[style]").firstNotNullOfOrNull { STYLE_URL_REGEX.find(it.attr("style"))?.groupValues?.get(1) }
        ?: return null
    return when {
        raw.startsWith("https://") -> raw
        raw.startsWith("//") -> "https:$raw"
        raw.startsWith("http://") -> "https://${raw.removePrefix("http://")}"
        else -> "$siteUrl/${raw.trimStart('/')}"
    }
}

internal fun JsonObject.gelbooruCommentHint(): CommentHint? {
    intValue("comment_count")?.let { return CommentHint(it.coerceAtLeast(0)) }
    return booleanValue("has_comments")?.let { if (it) CommentHint.Some else CommentHint.None }
}

private val PID_REGEX = Regex("[?&]pid=(\\d+)")
private val PROFILE_ID_REGEX = Regex("[?&]id=(\\d+)")
private val COMMENT_ID_REGEX = Regex("#(\\d+)")
private val DATE_REGEX = Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}(?::\\d{2})?")
private val STYLE_URL_REGEX = Regex("url\\(\\s*['\"]?([^'\")]+)")
