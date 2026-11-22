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
import com.theoriacodex.sources.common.requireSuccess
import com.theoriacodex.sources.common.sourceCall
import com.theoriacodex.sources.common.sourceRequest
import com.theoriacodex.sources.common.stringValue
import com.theoriacodex.sources.credentials.ApiKeyUse
import com.theoriacodex.sources.credentials.UserApiKey
import com.theoriacodex.sources.credentials.UserApiKeyCredentialLookup
import com.theoriacodex.sources.http.SourceHttpClient
import com.theoriacodex.sources.http.THEORIA_SOURCE_USER_AGENT

/** One site running Danbooru's software; every site shares the same API and post shape. */
data class DanbooruSite(
    val source: SourceKey,
    val label: String,
    val baseUrl: String,
    val searchTermLimit: SearchTermLimit? = null,
    /** Sent with every API request. Danbooru's Cloudflare challenges generic browser agents. */
    val headers: Map<String, String> = mapOf("User-Agent" to THEORIA_SOURCE_USER_AGENT),
    /** Keys go out as `login` + `api_key` query parameters, to [baseUrl] only. */
    val credentials: ApiKeyUse = ApiKeyUse.OPTIONAL,
) {
    companion object {
        /** API keys as Danbooru's query parameters; empty without keys. */
        fun authQuery(keys: UserApiKey?): Map<String, String> =
            keys?.let { mapOf("login" to it.userId, "api_key" to it.apiKey) }.orEmpty()

        /** Guests may search two tags, and an `order:` metatag counts as one of them. */
        val DANBOORU = DanbooruSite(
            source = SourceKey.DANBOORU,
            label = "Danbooru",
            baseUrl = "https://danbooru.donmai.us",
            searchTermLimit = SearchTermLimit(maxTerms = 2, rankedSortUsesTerm = true),
        )
        val AIBOORU = DanbooruSite(source = SourceKey.AIBOORU, label = "AIBooru", baseUrl = "https://aibooru.online")
    }
}

/**
 * Adapter for any [DanbooruSite]. Newest-first searches page by post id (`b<id>`) because guests
 * cannot open numbered pages past 1000 and new uploads would otherwise shift later pages; score
 * sorts cannot use id paging, so they keep numbered pages.
 */
class DanbooruSourceAdapter(
    private val site: DanbooruSite,
    private val httpClient: SourceHttpClient,
    /** [UserApiKey.userId] is the Danbooru login name. */
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
        // `animated` covers video, ugoira and GIFs; `filetype:` costs no term, so it serves when the guest limit is full
        // (static GIFs it lets through are left to the phone-side filter).
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

    /** `/users/<id>.json` for the join date and upload count, and the `ordfav:` post count for favorites. */
    override suspend fun accountDetails(creator: CreatorProfile): AccountDetails? = sourceCall {
        val name = danbooruAccountName(creator)?.takeIf { creator.source == sourceKey } ?: return@sourceCall null
        val user = creator.profileId?.let { id -> parseJsonObject(request("/users/$id.json"), gson, site.label) }
        val favorites = request("/counts/posts.json", mapOf("tags" to danbooruFavoritesTags(name)))
        parseDanbooruAccountDetails(user, parseDanbooruPostCount(parseJsonObject(favorites, gson, site.label)))
    }

    /** Favorites page by number: `ordfav:` lists by favorite order, so an id cursor (`b<id>`) would skip posts. */
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
            // The post exists but its files are withheld, which "not found" would misreport.
            throw SourceAdapterException(SourceFailureReason.AUTH_REQUIRED, "${site.label} doesn't show this post to this account")
        }
        parsePost(record)
    }

    override suspend fun findByFileHash(md5: String): Post? = sourceCall {
        searchTags("md5:$md5", idCursor = false, pageToken = null).items.firstOrNull()
    }

    /**
     * Live comments newest first, 100 to a page, without deleted ones. A full page means another
     * may follow, so the total is known only when everything fit on the first page. Danbooru comments
     * are flat, so [parent] is ignored.
     */
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
        // Fullness counts every raw record, so a dropped deleted comment never ends paging early.
        val hasMore = records.size() >= DANBOORU_COMMENTS_PAGE_SIZE
        val comments = records.mapNotNull { parseDanbooruComment(it, sourceKey, site.baseUrl) }
        CommentPage(
            comments = comments,
            total = comments.size.takeIf { pageIndex == 1 && !hasMore },
            nextPageToken = (pageIndex + 1).toString().takeIf { hasMore },
        )
    }

    override fun nativeCommentAnchor(commentId: String): String = "comment_$commentId"

    /** [idCursor] pages by the last post id (`b<id>`); otherwise [pageToken] is a page number. */
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
        // Fullness counts every raw record, so a malformed entry never ends paging early.
        val nextPageToken = when {
            rawRecords.size() < PAGE_SIZE -> null
            idCursor -> records.mapNotNull { it.longValue("id") }.minOrNull()?.let { "b$it" }
            else -> ((pageToken?.toIntOrNull() ?: 1) + 1).toString()
        }
        return canonicalPostPage(items = records.mapNotNull(::parsePost), nextPageToken = nextPageToken)
    }

    private suspend fun request(path: String, query: Map<String, String> = emptyMap()): String {
        val saved = credentials.keysFor(site.credentials, site.label)
        return sourceRequest(site.label) {
            httpClient.get(url = "${site.baseUrl}$path", query = query + DanbooruSite.authQuery(saved), headers = site.headers)
        }.requireSuccess("${site.label} request failed") { body ->
            // Danbooru explains rejected searches (such as the tag limit) in a JSON `message`.
            runCatching { parseJsonObject(body, gson, site.label) }.getOrNull()
                ?.stringValue("message")?.trim()?.takeIf(String::isNotBlank)
        }.body
    }

    /** Posts without a file URL are restricted to signed-in users, so they are left out. */
    private fun parsePost(raw: JsonObject): Post? {
        val id = raw.longValue("id")?.toString() ?: return null
        val fileUrl = httpsMediaUrl(raw.stringValue("file_url")) ?: return null
        val sampleUrl = httpsMediaUrl(raw.stringValue("large_file_url"))
        val fileExt = raw.stringValue("file_ext")
        val mediaAsset = raw.optionalJsonObject("media_asset")
        // Ugoira originals are zip archives; the site's sample is the playable webm rendition.
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
        /** The largest bounded thumbnail; the 180px default blurs in two-column cards. */
        const val CARD_PREVIEW_VARIANT = "720x720"
    }
}

/**
 * Danbooru tags never contain spaces, so a typed space joins words rather than adding a tag. The
 * site defaults to newest first, so only ranked sorts spend a term on an `order:` metatag.
 */
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
