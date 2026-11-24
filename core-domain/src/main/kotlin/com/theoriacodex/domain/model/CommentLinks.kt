package com.theoriacodex.domain.model

data class CommentTarget(val id: String, val parentId: String? = null)

fun commentLink(pageUrl: String, target: CommentTarget, nativeAnchor: String?): String {
    val base = pageUrl.substringBefore('#')
    val fragment = nativeAnchor?.takeIf { target.parentId == null }
        ?: (THEORIA_PREFIX + listOfNotNull(target.parentId, target.id).joinToString("/"))
    return "$base#$fragment"
}

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
