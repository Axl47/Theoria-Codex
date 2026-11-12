package com.theoriacodex.domain.model

/** The comment a link points at: [id], or a reply [id] under the top-level comment [parentId]. */
data class CommentTarget(val id: String, val parentId: String? = null)

/**
 * A post's page URL with a fragment naming [target]. A top-level comment uses the site's own anchor
 * when it has one ([nativeAnchor]), so the link also jumps there in a browser; replies, and sites
 * without anchors, use `#theoria-comment=<id>` or `#theoria-comment=<parentId>/<id>`, which browsers
 * ignore and Theoria reads back with [parseCommentTarget]. Any fragment already on [pageUrl] is replaced.
 */
fun commentLink(pageUrl: String, target: CommentTarget, nativeAnchor: String?): String {
    val base = pageUrl.substringBefore('#')
    val fragment = nativeAnchor?.takeIf { target.parentId == null }
        ?: (THEORIA_PREFIX + listOfNotNull(target.parentId, target.id).joinToString("/"))
    return "$base#$fragment"
}

/**
 * Reads the comment a link's fragment (without `#`) points at: Theoria's own form, Gelbooru's `c<id>`,
 * or Danbooru's `comment-<id>` / `comment_<id>`. Null for any other fragment. Ids are numeric on every
 * source with comments, so anything else is rejected.
 */
fun parseCommentTarget(fragment: String?): CommentTarget? {
    val value = fragment?.trim()?.removePrefix("#")?.takeIf(String::isNotEmpty) ?: return null
    if (value.startsWith(THEORIA_PREFIX)) {
        val parts = value.removePrefix(THEORIA_PREFIX).split('/')
        if (parts.size !in 1..2 || parts.any { !it.isCommentId() }) return null
        return if (parts.size == 2) CommentTarget(parts[1], parentId = parts[0]) else CommentTarget(parts[0])
    }
    return NATIVE_ANCHOR.matchEntire(value)?.groupValues?.get(1)?.let(::CommentTarget)
}

private fun String.isCommentId(): Boolean = isNotEmpty() && length <= MAX_ID_LENGTH && all(Char::isDigit)

private const val THEORIA_PREFIX = "theoria-comment="
private const val MAX_ID_LENGTH = 20
private val NATIVE_ANCHOR = Regex("(?:c|comment[-_])(\\d{1,20})")
