package com.theoriacodex.sources.gelbooru

import com.google.gson.JsonObject
import com.theoriacodex.domain.model.CommentHint
import com.theoriacodex.domain.model.PostComment
import com.theoriacodex.sources.common.booleanValue
import com.theoriacodex.sources.common.htmlFragmentToText
import com.theoriacodex.sources.common.intValue
import com.theoriacodex.sources.common.parseBooruDateTimeMs
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser

/** One page of comments scraped from a post page. [nextPid] is the `pid` offset of the following page, if any. */
internal data class GelbooruHtmlComments(val comments: List<PostComment>, val nextPid: Int?)

/**
 * Comments from a DAPI `s=comment` answer, which is XML only (`json=1` is ignored). Returns null when
 * the answer is not comment XML, such as gelbooru.com's `Disabled due to abuse.` page, so the caller can
 * fall back to the post page. Attribute values arrive unescaped and are plain text; `creator_id` names the commenter's account.
 */
internal fun parseGelbooruCommentsXml(body: String, site: GelbooruSite): List<PostComment>? {
    val root = Jsoup.parse(body, "", Parser.xmlParser()).selectFirst("comments") ?: return null
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

/**
 * Comments scraped from a post page (`index.php?page=post&s=view&id=`), for sites whose DAPI comments
 * are disabled. Two layouts of the same engine are read:
 * - gelbooru.com: each comment is a `div.commentAvatar` followed by a `div.commentBody` holding the author
 *   link (`a > b`), `posted on <date> • #<id>`, then the text after the first blank line; vote and flag
 *   `span`s trail it.
 * - Safebooru: `div#c<id>` holding `div.col1` (author link, `<b>posted on <date>`) and `div.col2` (text).
 *
 * Pages are numbered by `pid` offsets in `#paginator` links. Relative avatar paths resolve against the site URL.
 * The commenter's account id is the `id` of the author link's profile href; Safebooru's links carry only `uname`.
 */
internal fun parseGelbooruCommentsHtml(html: String, site: GelbooruSite, currentPid: Int): GelbooruHtmlComments {
    val document = Jsoup.parse(html, "${site.siteUrl}/")
    val bodies = document.select("div.commentBody")
    val comments = if (bodies.isNotEmpty()) {
        bodies.mapNotNull { parseCommentBody(it, site) }
    } else {
        document.select("div[id~=^c\\d+$]").mapNotNull { parseColumnComment(it, site) }
    }
    val nextPid = document.select("#paginator a[href], .paginator a[href]")
        .mapNotNull { link -> PID_REGEX.find(link.attr("href"))?.groupValues?.get(1)?.toIntOrNull() }
        .filter { it > currentPid }
        .minOrNull()
    return GelbooruHtmlComments(comments, nextPid)
}

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

/** The comment text follows the header's blank line (two consecutive `<br>`s). */
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

/** The account id in a profile link's `id=` parameter; null for links that name the user instead (`uname=`). */
private fun profileId(link: Element): String? = PROFILE_ID_REGEX.find(link.attr("href"))?.groupValues?.get(1)

private fun Node.isBr(): Boolean = this is Element && tagName() == "br"

/** The avatar is an `img`, or a CSS `background: url(...)` on `div.profileAvatar`. */
private fun avatarUrl(avatar: Element, siteUrl: String): String? {
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

/**
 * Listing hint from search JSON: Gelbooru 0.2 sites give an exact `comment_count`; gelbooru.com gives
 * `has_comments` as a boolean or the string `"true"`/`"false"`. Null when the record has neither.
 */
internal fun JsonObject.gelbooruCommentHint(): CommentHint? {
    intValue("comment_count")?.let { return CommentHint(it.coerceAtLeast(0)) }
    return booleanValue("has_comments")?.let { if (it) CommentHint.Some else CommentHint.None }
}

private val PID_REGEX = Regex("[?&]pid=(\\d+)")
private val PROFILE_ID_REGEX = Regex("[?&]id=(\\d+)")
private val COMMENT_ID_REGEX = Regex("#(\\d+)")
private val DATE_REGEX = Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}(?::\\d{2})?")
private val STYLE_URL_REGEX = Regex("url\\(\\s*['\"]?([^'\")]+)")
