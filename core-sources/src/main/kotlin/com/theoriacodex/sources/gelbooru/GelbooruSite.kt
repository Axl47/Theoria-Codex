package com.theoriacodex.sources.gelbooru

import com.theoriacodex.domain.adapter.SourceCapabilities
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.credentials.ApiKeyUse
import com.theoriacodex.sources.credentials.UserApiKey
import com.theoriacodex.sources.http.BROWSER_USER_AGENT

data class GelbooruSite(
    val source: SourceKey,
    val label: String,
    val siteUrl: String,
    val apiUrl: String = "$siteUrl/index.php",
    val autocompleteUrl: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val credentials: ApiKeyUse = ApiKeyUse.OPTIONAL,
    val postLookup: GelbooruPostLookup = GelbooruPostLookup.ID_PARAMETER,
    val orGroup: GelbooruOrGroup? = null,
    val newestSortTag: String? = null,
    val scoreSortTag: String = "sort:score:desc",
    val creatorPosts: Boolean = false,
    val relatedPosts: Boolean = false,
    val tagList: GelbooruTagList = GelbooruTagList.JSON,
    val commentAnchors: Boolean = true,
    val publicProfiles: Boolean = false,
    val favoritesList: Boolean = false,
    val exactTags: Boolean = false,
) {
    fun creatorProfile(owner: String?, creatorId: String?): CreatorProfile? {
        val name = owner?.trim().orEmpty()
        val id = creatorId?.trim()?.ifBlank { null }
        return CreatorProfile(
            source = source,
            displayName = name.ifBlank { id } ?: return null,
            profileId = id,
            profileUrl = id?.let { "$siteUrl/index.php?page=account&s=profile&id=$it" },
            uploadsQuery = name.ifBlank { null }?.let { "user:$it" },
        )
    }

    companion object {
        fun authQuery(keys: UserApiKey?): Map<String, String> =
            keys?.let { mapOf("user_id" to it.userId, "api_key" to it.apiKey) }.orEmpty()

        val GELBOORU = GelbooruSite(
            source = SourceKey.GELBOORU,
            label = "Gelbooru",
            siteUrl = "https://gelbooru.com",
            orGroup = GelbooruOrGroup(prefix = "{", separator = " ~ ", suffix = "}"),
            newestSortTag = "sort:id:desc",
            creatorPosts = true,
            relatedPosts = true,
            commentAnchors = false,
            publicProfiles = true,
            favoritesList = true,
            exactTags = true,
        )

        val RULE34XXX = GelbooruSite(
            source = SourceKey.RULE34XXX,
            label = "rule34.xxx",
            siteUrl = "https://rule34.xxx",
            apiUrl = "https://api.rule34.xxx/index.php",
            autocompleteUrl = "https://api.rule34.xxx/autocomplete.php",
            headers = mapOf("User-Agent" to BROWSER_USER_AGENT),
            credentials = ApiKeyUse.REQUIRED,
            postLookup = GelbooruPostLookup.ID_TAG,
            scoreSortTag = "sort:score",
        )
    }
}

enum class GelbooruPostLookup {
    ID_PARAMETER,

    ID_TAG,
}

enum class GelbooruTagList {
    JSON,

    DETECT,
}

data class GelbooruOrGroup(val prefix: String, val separator: String, val suffix: String)
