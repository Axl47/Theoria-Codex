package com.theoriacodex.sources.pixiv

import com.google.gson.JsonObject
import com.theoriacodex.domain.model.AccountDetails
import com.theoriacodex.sources.common.intValue
import com.theoriacodex.sources.common.optionalJsonObject
import com.theoriacodex.sources.common.stringValue

internal fun parsePixivAccountDetails(root: JsonObject): AccountDetails? {
    val user = root.optionalJsonObject("user")
    val profile = root.optionalJsonObject("profile")
    val works = listOfNotNull(profile?.intValue("total_illusts"), profile?.intValue("total_manga"))
    val details = AccountDetails(
        avatarUrl = user?.optionalJsonObject("profile_image_urls")?.stringValue("medium")?.trim()?.takeIf(String::isNotBlank),
        bio = user?.stringValue("comment")?.trim()?.takeIf(String::isNotBlank),
        uploadCount = works.takeIf { it.isNotEmpty() }?.sum(),
        favoriteCount = profile?.intValue("total_illust_bookmarks_public"),
    )
    return details.takeIf { it != AccountDetails() }
}
