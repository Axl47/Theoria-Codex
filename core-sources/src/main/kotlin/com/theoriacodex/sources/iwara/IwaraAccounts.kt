package com.theoriacodex.sources.iwara

import com.google.gson.JsonObject
import com.theoriacodex.domain.model.AccountDetails
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.sources.common.intValue
import com.theoriacodex.sources.common.optionalJsonObject
import com.theoriacodex.sources.common.parseOffsetDateTimeMs
import com.theoriacodex.sources.common.stringValue

internal fun iwaraUsername(creator: CreatorProfile): String? =
    creator.profileUrl?.let { PROFILE_USERNAME_REGEX.find(it)?.groupValues?.get(1) }

internal fun parseIwaraAccountDetails(profile: JsonObject, followerCount: Int?): AccountDetails? {
    val user = profile.optionalJsonObject("user")
    val details = AccountDetails(
        avatarUrl = user?.optionalJsonObject("avatar")?.let(::iwaraAssetUrl),
        bio = profile.stringValue("body")?.trim()?.takeIf(String::isNotBlank),
        joinedAtEpochMs = parseOffsetDateTimeMs(user?.stringValue("createdAt")),
        followerCount = followerCount,
    )
    return details.takeIf { it != AccountDetails() }
}

private val PROFILE_USERNAME_REGEX = Regex("/profile/([^/?#]+)")
