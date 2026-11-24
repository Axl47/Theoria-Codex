package com.theoriacodex.sources.danbooru

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.theoriacodex.domain.model.CommentHint
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.PostComment
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.common.booleanValue
import com.theoriacodex.sources.common.longValue
import com.theoriacodex.sources.common.optionalJsonObject
import com.theoriacodex.sources.common.parseOffsetDateTimeMs
import com.theoriacodex.sources.common.stringValue

internal const val DANBOORU_COMMENTS_PAGE_SIZE = 100

internal const val DANBOORU_COMMENT_FIELDS = "id,body,created_at,is_deleted,creator"

internal fun parseDanbooruComment(element: JsonElement, source: SourceKey, baseUrl: String): PostComment? {
    val raw = element.takeIf { it.isJsonObject }?.asJsonObject ?: return null
    if (raw.booleanValue("is_deleted") == true) return null
    val id = raw.longValue("id")?.toString() ?: return null
    val creator = raw.optionalJsonObject("creator")
    val name = creator?.stringValue("name")?.trim().orEmpty()
    val userId = creator?.longValue("id")?.toString()
    return PostComment(
        id = id,
        authorName = name,
        authorAvatarUrl = null,
        body = danbooruDTextToPlain(raw.stringValue("body").orEmpty()),
        createdAtEpochMs = parseOffsetDateTimeMs(raw.stringValue("created_at")),
        author = userId?.let {
            CreatorProfile(
                source = source,
                displayName = name.ifBlank { it },
                profileId = it,
                profileUrl = "$baseUrl/users/$it",
                uploadsQuery = name.ifBlank { null }?.let { user -> "user:$user" },
            )
        },
    )
}

internal fun danbooruDTextToPlain(dtext: String): String =
    dtext
        .replace(LINK_REGEX, "$1")
        .replace(FORMATTING_TAG_REGEX, "")
        .trim()

internal fun JsonObject.danbooruCommentHint(): CommentHint? = when {
    !has("last_commented_at") -> null
    get("last_commented_at").isJsonNull -> CommentHint.None
    else -> CommentHint.Some
}

private val LINK_REGEX = Regex("\"([^\"\\n]+)\":(?:\\[[^\\]\\s]+]|(?:https?://|/)\\S*[^\\s,.;:!?)])")
private val FORMATTING_TAG_REGEX = Regex("\\[/?(?:b|i|u|s|spoilers?|tn|quote|code|nodtext)]", RegexOption.IGNORE_CASE)
