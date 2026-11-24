package com.theoriacodex.sources.danbooru

import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.adapter.AnimatedSearchTerm
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.theoriacodex.domain.adapter.AccountProfileSourceAdapter
import com.theoriacodex.domain.adapter.CommentsSourceAdapter
import com.theoriacodex.domain.adapter.CreatorPostsSourceAdapter
import com.theoriacodex.domain.adapter.FileHashLookupSourceAdapter
import com.theoriacodex.domain.adapter.Page
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceCapabilities
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.adapter.TagCountLookupSourceAdapter
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.AccountDetails
import com.theoriacodex.domain.model.AccountFeed
import com.theoriacodex.domain.model.CommentPage
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostComment
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.model.contentHashOrNull
import com.theoriacodex.domain.query.SearchTermLimit
import com.theoriacodex.sources.common.autocompleteTagCounts
import com.theoriacodex.sources.common.booruFull
import com.theoriacodex.sources.common.booruPreview
import com.theoriacodex.sources.common.canonicalPostPage
import com.theoriacodex.sources.common.durationFieldMs
import com.theoriacodex.sources.common.httpsMediaUrl
import com.theoriacodex.sources.common.intValue
import com.theoriacodex.sources.common.keysFor
import com.theoriacodex.sources.common.longValue
import com.theoriacodex.sources.common.mimeFromUrlOrExt
import com.theoriacodex.sources.common.optionalJsonArray
import com.theoriacodex.sources.common.optionalJsonObject
import com.theoriacodex.sources.common.originUrlOrNull
import com.theoriacodex.sources.common.parseJsonArray
import com.theoriacodex.sources.common.parseJsonObject
import com.theoriacodex.sources.common.parseOffsetDateTimeMs
import com.theoriacodex.sources.common.sourceCall
import com.theoriacodex.sources.common.getChecked
import com.theoriacodex.sources.common.stringValue
import com.theoriacodex.sources.credentials.ApiKeyUse
import com.theoriacodex.sources.credentials.UserApiKey
import com.theoriacodex.sources.credentials.UserApiKeyCredentialLookup
import com.theoriacodex.sources.http.SourceHttpClient
import com.theoriacodex.sources.http.THEORIA_SOURCE_USER_AGENT

data class DanbooruSite(
    val source: SourceKey,
    val label: String,
    val baseUrl: String,
    val searchTermLimit: SearchTermLimit? = null,
    val headers: Map<String, String> = mapOf("User-Agent" to THEORIA_SOURCE_USER_AGENT),
    val credentials: ApiKeyUse = ApiKeyUse.OPTIONAL,
) {
    companion object {
        fun authQuery(keys: UserApiKey?): Map<String, String> =
            keys?.let { mapOf("login" to it.userId, "api_key" to it.apiKey) }.orEmpty()

        val DANBOORU = DanbooruSite(
            source = SourceKey.DANBOORU,
            label = "Danbooru",
            baseUrl = "https://danbooru.donmai.us",
            searchTermLimit = SearchTermLimit(maxTerms = 2, rankedSortUsesTerm = true),
        )
        val AIBOORU = DanbooruSite(source = SourceKey.AIBOORU, label = "AIBooru", baseUrl = "https://aibooru.online")
    }
}

class DanbooruSourceAdapter(
    private val site: DanbooruSite,
    private val httpClient: SourceHttpClient,
    private val credentials: UserApiKeyCredentialLookup = { null },
    private val gson: Gson = Gson(),
) : SourceAdapter, TagCountLookupSourceAdapter, CreatorPostsSourceAdapter, CommentsSourceAdapter,
    AccountProfileSourceAdapter, FileHashLookupSourceAdapter {
    override val sourceKey: SourceKey = site.source

    override val capabilities: SourceCapabilities = SourceCapabilities(
        supportsSortNewest = true,
        supportsSortPopular = true,
        supportsSortTop = true,
        supportsSortRandom = false,
        supportsExcludeTagsServerSide = true,
        searchTermLimit = site.searchTermLimit,
        animatedTerm = AnimatedSearchTerm(SearchTerm("animated"), overLimit = SearchTerm("filetype:gif,mp4,webm,zip")),
    )

    override suspend fun search(query: Query, pageToken: String?): Page<Post> = sourceCall {
        searchTags(compileTags(query), idCursor = query.sort == SortMode.NEWEST, pageToken)
    }

    override suspend fun searchCreatorPosts(creator: CreatorProfile, pageToken: String?): Page<Post> = sourceCall {
        val artistTag = creator.uploadsQuery?.trim()
        if (creator.source != sourceKey || artistTag.isNullOrBlank()) return@sourceCall canonicalPostPage(emptyList(), null)
        searchTags(artistTag, idCursor = true, pageToken)
    }

    override fun accountFeeds(creator: CreatorProfile): List<AccountFeed> =
        if (creator.source == sourceKey) danbooruAccountFeeds(creator) else emptyList()

    override suspend fun accountDetails(creator: CreatorProfile): AccountDetails? = sourceCall {
        val name = danbooruAccountName(creator)?.takeIf { creator.source == sourceKey } ?: return@sourceCall null
        val user = creator.profileId?.let { id -> parseJsonObject(request("/users/$id.json"), gson, site.label) }
        val favorites = request("/counts/posts.json", mapOf("tags" to danbooruFavoritesTags(name)))
        parseDanbooruAccountDetails(user, parseDanbooruPostCount(parseJsonObject(favorites, gson, site.label)))
    }

    override suspend fun accountPosts(creator: CreatorProfile, feed: AccountFeed, pageToken: String?): Page<Post> = sourceCall {
        val name = danbooruAccountName(creator)?.takeIf { creator.source == sourceKey }
            ?: return@sourceCall canonicalPostPage(emptyList(), null)
        when (feed) {
            AccountFeed.UPLOADS -> searchCreatorPosts(creator, pageToken)
            AccountFeed.FAVORITES -> searchTags(danbooruFavoritesTags(name), idCursor = false, pageToken)
        }
    }

    override suspend fun trendingTags(limit: Int): List<TagSuggestion> = sourceCall {
        val response = request(
            path = "/tags.json",
            query = mapOf("limit" to limit.coerceIn(1, 50).toString(), "search[order]" to "count"),
        )
        parseJsonArray(response, gson, site.label).mapNotNull { parseTagSuggestion(it, "trending") }
    }

    override suspend fun autocompleteTags(prefix: String, limit: Int): List<TagSuggestion> = sourceCall {
        if (prefix.isBlank()) return@sourceCall emptyList()
        val response = request(
            path = "/tags.json",
            query = mapOf(
                "limit" to limit.coerceIn(1, 30).toString(),
                "search[name_matches]" to "${prefix.trim()}*",
                "search[order]" to "count",
            ),
        )
        parseJsonArray(response, gson, site.label).mapNotNull { parseTagSuggestion(it, "tag") }
    }

    override suspend fun fetchTagCounts(tags: List<String>): Map<String, Int> = sourceCall {
        autocompleteTagCounts(sourceKey, tags, ::autocompleteTags)
    }

    override suspend fun resolvePost(id: PostId): Post? = sourceCall {
        if (id.source != sourceKey) return@sourceCall null
        val record = parseJsonObject(request(path = "/posts/${id.sourcePostId}.json"), gson, site.label)
        if (record.has("id") && record.stringValue("file_url").isNullOrBlank()) {
            throw SourceAdapterException(SourceFailureReason.AUTH_REQUIRED, "${site.label} doesn't show this post to this account")
        }
        parsePost(record)
    }

    override suspend fun findByFileHash(md5: String): Post? = sourceCall {
        searchTags("md5:$md5", idCursor = false, pageToken = null).items.firstOrNull()
    }

    override suspend fun comments(post: Post, pageToken: String?, parent: PostComment?): CommentPage = sourceCall {
        if (post.id.source != sourceKey) return@sourceCall CommentPage(emptyList(), total = 0, nextPageToken = null)
        val pageIndex = pageToken?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val response = request(
            path = "/comments.json",
            query = mapOf(
                "group_by" to "comment",
                "search[post_id]" to post.id.sourcePostId,
                "search[is_deleted]" to "false",
                "limit" to DANBOORU_COMMENTS_PAGE_SIZE.toString(),
                "page" to pageIndex.toString(),
                "only" to DANBOORU_COMMENT_FIELDS,
            ),
        )
        val records = parseJsonArray(response, gson, site.label)
        val hasMore = records.size() >= DANBOORU_COMMENTS_PAGE_SIZE
        val comments = records.mapNotNull { parseDanbooruComment(it, sourceKey, site.baseUrl) }
        CommentPage(
            comments = comments,
            total = comments.size.takeIf { pageIndex == 1 && !hasMore },
            nextPageToken = (pageIndex + 1).toString().takeIf { hasMore },
        )
    }

    override fun nativeCommentAnchor(commentId: String): String = "comment_$commentId"

    private suspend fun searchTags(tags: String, idCursor: Boolean, pageToken: String?): Page<Post> {
        val response = request(
            path = "/posts.json",
            query = mapOf(
                "limit" to PAGE_SIZE.toString(),
                "page" to (pageToken?.takeIf(String::isNotBlank) ?: "1"),
                "tags" to tags,
            ),
        )
        val rawRecords = parseJsonArray(response, gson, site.label)
        val records = rawRecords.mapNotNull { element -> element.takeIf(JsonElement::isJsonObject)?.asJsonObject }
        val nextPageToken = when {
            rawRecords.size() < PAGE_SIZE -> null
            idCursor -> records.mapNotNull { it.longValue("id") }.minOrNull()?.let { "b$it" }
            else -> ((pageToken?.toIntOrNull() ?: 1) + 1).toString()
        }
        return canonicalPostPage(items = records.mapNotNull(::parsePost), nextPageToken = nextPageToken)
    }

    private suspend fun request(path: String, query: Map<String, String> = emptyMap()): String {
        val saved = credentials.keysFor(site.credentials, site.label)
        return httpClient.getChecked(
            site.label,
            "${site.baseUrl}$path",
            query = query + DanbooruSite.authQuery(saved),
            headers = site.headers,
            providerMessage = { body ->
                runCatching { parseJsonObject(body, gson, site.label) }.getOrNull()
                    ?.stringValue("message")?.trim()?.takeIf(String::isNotBlank)
            },
        )
    }

    private fun parsePost(raw: JsonObject): Post? {
        val id = raw.longValue("id")?.toString() ?: return null
        val fileUrl = httpsMediaUrl(raw.stringValue("file_url")) ?: return null
        val sampleUrl = httpsMediaUrl(raw.stringValue("large_file_url"))
        val fileExt = raw.stringValue("file_ext")
        val mediaAsset = raw.optionalJsonObject("media_asset")
        val ugoiraSample = sampleUrl?.takeIf { fileExt == UGOIRA_FILE_EXT }
        val fullUrl = ugoiraSample ?: fileUrl
        val fullMime = mimeFromUrlOrExt(fullUrl, fileExt.takeIf { ugoiraSample == null })
        val previewUrl = httpsMediaUrl(mediaAsset?.variantUrl(CARD_PREVIEW_VARIANT) ?: raw.stringValue("preview_file_url"))
        val tags = raw.tagList("tag_string")
        val artistTag = raw.tagList("tag_string_artist").firstOrNull()
        return Post(
            id = PostId(sourceKey, id),
            preview = booruPreview(previewUrl, fullMime),
            full = booruFull(fullUrl, fullMime, sampleUrl),
            pageUrl = "${site.baseUrl}/posts/$id",
            width = raw.intValue("image_width"),
            height = raw.intValue("image_height"),
            canonicalTags = tags,
            rawTags = tags,
            authorName = artistTag?.displayTag(),
            createdAtEpochMs = parseOffsetDateTimeMs(raw.stringValue("created_at")),
            durationMs = mediaAsset?.durationFieldMs("duration", multiplier = 1_000L),
            commentHint = raw.danbooruCommentHint(),
            contentHash = contentHashOrNull(raw.stringValue("md5")),
            originUrl = originUrlOrNull(raw.stringValue("source"), raw.longValue("pixiv_id")),
            creatorProfile = artistTag?.let { tag ->
                CreatorProfile(
                    source = sourceKey,
                    displayName = tag.displayTag(),
                    profileId = tag,
                    profileUrl = "${site.baseUrl}/posts?tags=$tag",
                    uploadsQuery = tag,
                )
            },
        )
    }

    private fun parseTagSuggestion(raw: JsonElement, type: String): TagSuggestion? {
        val obj = raw.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val name = obj.stringValue("name")?.trim().orEmpty()
        if (name.isBlank()) return null
        return TagSuggestion(text = name, type = type, count = obj.intValue("post_count") ?: obj.intValue("count"))
    }

    private companion object {
        const val PAGE_SIZE = 40
        const val UGOIRA_FILE_EXT = "zip"
        const val CARD_PREVIEW_VARIANT = "720x720"
    }
}

private fun compileTags(query: Query): String {
    val include = query.includeTags.mapNotNull(::danbooruTag)
    val exclude = query.excludeTags.mapNotNull(::danbooruTag).map { "-$it" }
    val order = when (query.sort) {
        SortMode.NEWEST, SortMode.RANDOM -> null
        SortMode.POPULAR, SortMode.TOP -> "order:score"
    }
    return (include + exclude + listOfNotNull(order)).joinToString(" ")
}

private fun danbooruTag(value: String): String? =
    value.trim().replace(WHITESPACE_REGEX, "_").takeIf(String::isNotBlank)

private fun JsonObject.tagList(name: String): List<String> =
    stringValue(name)?.split(' ')?.filter(String::isNotBlank).orEmpty()

private fun JsonObject.variantUrl(type: String): String? =
    optionalJsonArray("variants")
        ?.mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }
        ?.firstOrNull { it.stringValue("type") == type }
        ?.stringValue("url")

private fun String.displayTag(): String = replace('_', ' ')

private val WHITESPACE_REGEX = Regex("\\s+")
