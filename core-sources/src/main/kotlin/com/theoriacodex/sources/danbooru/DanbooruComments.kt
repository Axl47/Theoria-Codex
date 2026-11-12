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

/** Comments per `/comments.json` page; a page that comes back this full may have a successor. */
internal const val DANBOORU_COMMENTS_PAGE_SIZE = 100

/** Fields requested for comments; everything else Danbooru sends is unused. */
internal const val DANBOORU_COMMENT_FIELDS = "id,body,created_at,is_deleted,creator"

/**
 * Maps one `/comments.json` record; deleted comments and records without an id are dropped. The
 * commenter's account is the record's `creator` (`id` and `name`) on the site at [baseUrl].
 */
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

/**
 * Reduces DText to readable plain text: simple formatting tags (quotes included, keeping their
 * text) are removed and `"label":url` links become their label. Everything else is kept as written.
 */
internal fun danbooruDTextToPlain(dtext: String): String =
    dtext
        .replace(LINK_REGEX, "$1")
        .replace(FORMATTING_TAG_REGEX, "")
        .trim()

/** Listing hint: a non-null `last_commented_at` means comments exist; an explicit null means none. */
internal fun JsonObject.danbooruCommentHint(): CommentHint? = when {
    !has("last_commented_at") -> null
    get("last_commented_at").isJsonNull -> CommentHint.None
    else -> CommentHint.Some
}

private val LINK_REGEX = Regex("\"([^\"\\n]+)\":(?:\\[[^\\]\\s]+]|(?:https?://|/)\\S*[^\\s,.;:!?)])")
private val FORMATTING_TAG_REGEX = Regex("\\[/?(?:b|i|u|s|spoilers?|tn|quote|code|nodtext)]", RegexOption.IGNORE_CASE)
