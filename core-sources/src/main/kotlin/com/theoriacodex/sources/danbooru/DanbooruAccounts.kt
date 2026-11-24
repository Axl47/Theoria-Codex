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

internal fun danbooruAccountName(creator: CreatorProfile): String? =
    creator.uploadsQuery?.trim()?.takeIf { it.startsWith(USER_QUERY_PREFIX) }
        ?.removePrefix(USER_QUERY_PREFIX)?.takeIf(String::isNotBlank)

internal fun danbooruAccountFeeds(creator: CreatorProfile): List<AccountFeed> =
    if (danbooruAccountName(creator) == null) emptyList() else listOf(AccountFeed.UPLOADS, AccountFeed.FAVORITES)

internal fun danbooruFavoritesTags(name: String): String = "ordfav:$name"

internal fun parseDanbooruAccountDetails(user: JsonObject?, favoriteCount: Int?): AccountDetails? =
    AccountDetails(
        joinedAtEpochMs = parseOffsetDateTimeMs(user?.stringValue("created_at")),
        uploadCount = user?.intValue("post_upload_count"),
        favoriteCount = favoriteCount,
    ).takeIf { it != AccountDetails() }

internal fun parseDanbooruPostCount(root: JsonObject): Int? = root.optionalJsonObject("counts")?.intValue("posts")
