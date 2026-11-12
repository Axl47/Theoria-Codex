package com.theoriacodex.sources.danbooru

import com.google.gson.JsonObject
import com.theoriacodex.domain.model.AccountDetails
import com.theoriacodex.domain.model.AccountFeed
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.sources.common.intValue
import com.theoriacodex.sources.common.optionalJsonObject
import com.theoriacodex.sources.common.parseOffsetDateTimeMs
import com.theoriacodex.sources.common.stringValue

private const val USER_QUERY_PREFIX = "user:"

/**
 * The account name of a user creator, whose uploads are searched as `user:<name>`. Artist-tag
 * creators (post uploaders) search the bare tag and are not accounts, so they return null.
 */
internal fun danbooruAccountName(creator: CreatorProfile): String? =
    creator.uploadsQuery?.trim()?.takeIf { it.startsWith(USER_QUERY_PREFIX) }
        ?.removePrefix(USER_QUERY_PREFIX)?.takeIf(String::isNotBlank)

/** An account offers its uploads and, when public, its favorites; anything else offers nothing. */
internal fun danbooruAccountFeeds(creator: CreatorProfile): List<AccountFeed> =
    if (danbooruAccountName(creator) == null) emptyList() else listOf(AccountFeed.UPLOADS, AccountFeed.FAVORITES)

/** The search that lists the account [name]'s favorites, ordered by when they were favorited. */
internal fun danbooruFavoritesTags(name: String): String = "ordfav:$name"

/**
 * Header facts from a `/users/<id>.json` record plus the `counts/posts.json?tags=ordfav:<name>` total.
 * Danbooru has no avatars or bios in this record, and counts private favorites as 0.
 */
internal fun parseDanbooruAccountDetails(user: JsonObject?, favoriteCount: Int?): AccountDetails? =
    AccountDetails(
        joinedAtEpochMs = parseOffsetDateTimeMs(user?.stringValue("created_at")),
        uploadCount = user?.intValue("post_upload_count"),
        favoriteCount = favoriteCount,
    ).takeIf { it != AccountDetails() }

/** `posts` of a `counts/posts.json` answer (`{"counts":{"posts":N}}`). */
internal fun parseDanbooruPostCount(root: JsonObject): Int? = root.optionalJsonObject("counts")?.intValue("posts")
