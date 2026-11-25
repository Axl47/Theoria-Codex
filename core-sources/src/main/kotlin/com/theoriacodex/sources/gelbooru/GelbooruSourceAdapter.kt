package com.theoriacodex.sources.gelbooru

import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.adapter.AnimatedSearchTerm
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.theoriacodex.domain.adapter.AccountProfileSourceAdapter
import com.theoriacodex.domain.adapter.CommentsSourceAdapter
import com.theoriacodex.domain.adapter.CreatorPostsSourceAdapter
import com.theoriacodex.domain.adapter.FileHashLookupSourceAdapter
import com.theoriacodex.domain.adapter.Page
import com.theoriacodex.domain.adapter.RelatedPostsSourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceCapabilities
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.adapter.TagCountLookupSourceAdapter
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.coroutines.mapConcurrent
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.AccountDetails
import com.theoriacodex.domain.model.AccountFeed
import com.theoriacodex.domain.model.CommentPage
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostComment
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.contentHashOrNull
import com.theoriacodex.domain.tags.decodeHtmlEntities
import com.theoriacodex.sources.common.ambiguousDurationFieldMs
import com.theoriacodex.sources.common.asStringOrNull
import com.theoriacodex.sources.common.autocompleteTagCounts
import com.theoriacodex.sources.common.booruFull
import com.theoriacodex.sources.common.booruPreview
import com.theoriacodex.sources.common.canonicalPostPage
import com.theoriacodex.sources.common.durationFieldMs
import com.theoriacodex.sources.common.firstDurationMs
import com.theoriacodex.sources.common.httpsMediaUrl
import com.theoriacodex.sources.common.intValue
import com.theoriacodex.sources.common.parseGelbooruDateTimeMs
import com.theoriacodex.sources.common.keysFor
import com.theoriacodex.sources.common.longValue
import com.theoriacodex.sources.common.mimeFromUrlOrExt
import com.theoriacodex.sources.common.normalizeBooruTag
import com.theoriacodex.sources.common.objectList
import com.theoriacodex.sources.common.originUrlOrNull
import com.theoriacodex.sources.common.parseJsonArray
import com.theoriacodex.sources.common.parseJsonElement
import com.theoriacodex.sources.common.sourceCall
import com.theoriacodex.sources.common.getChecked
import com.theoriacodex.sources.common.stringValue
import com.theoriacodex.sources.credentials.UserApiKeyCredentialLookup
import com.theoriacodex.sources.http.SourceHttpClient

fun GelbooruSourceAdapter(
    site: GelbooruSite,
    httpClient: SourceHttpClient,
    credentials: UserApiKeyCredentialLookup = { null },
): GelbooruFamilySourceAdapter {
    val core = GelbooruFamilyAdapter(site, httpClient, credentials)
    return when {
        site.creatorPosts && site.relatedPosts -> object :
            GelbooruFamilySourceAdapter by core,
            CreatorPostsSourceAdapter by core,
            RelatedPostsSourceAdapter by core {}
        site.creatorPosts -> object : GelbooruFamilySourceAdapter by core, CreatorPostsSourceAdapter by core {}
        site.relatedPosts -> object : GelbooruFamilySourceAdapter by core, RelatedPostsSourceAdapter by core {}
        else -> object : GelbooruFamilySourceAdapter by core {}
    }
}

interface GelbooruFamilySourceAdapter :
    SourceAdapter, TagCountLookupSourceAdapter, CommentsSourceAdapter, AccountProfileSourceAdapter, FileHashLookupSourceAdapter

private class GelbooruFamilyAdapter(
    private val site: GelbooruSite,
    private val httpClient: SourceHttpClient,
    private val credentials: UserApiKeyCredentialLookup,
    private val gson: Gson = Gson(),
) : GelbooruFamilySourceAdapter, CreatorPostsSourceAdapter, RelatedPostsSourceAdapter {
    override val sourceKey = site.source

    override val capabilities: SourceCapabilities = SourceCapabilities(
        supportsSortNewest = true,
        supportsSortPopular = true,
        supportsSortTop = true,
        supportsSortRandom = false,
        supportsExcludeTagsServerSide = true,
        supportsGroupedIncludeTagsServerSide = site.orGroup != null,
        requiresExactTags = site.exactTags,
        animatedTerm = AnimatedSearchTerm(SearchTerm("animated")),
    )

    override suspend fun search(query: Query, pageToken: String?): Page<Post> = sourceCall {
        searchPosts(compileTags(query), pageToken)
    }

    override suspend fun searchCreatorPosts(creator: CreatorProfile, pageToken: String?): Page<Post> = sourceCall {
        val uploadsQuery = creator.uploadsQuery?.trim()
        if (creator.source != sourceKey || uploadsQuery.isNullOrBlank()) return@sourceCall canonicalPostPage(emptyList(), null)
        searchPosts(uploadsQuery, pageToken)
    }

    override fun accountFeeds(creator: CreatorProfile): List<AccountFeed> {
        if (creator.source != sourceKey) return emptyList()
        return listOfNotNull(
            AccountFeed.UPLOADS.takeIf { !creator.uploadsQuery.isNullOrBlank() },
            AccountFeed.FAVORITES.takeIf { site.favoritesList && creator.profileId != null },
        )
    }

    override suspend fun accountDetails(creator: CreatorProfile): AccountDetails? = sourceCall {
        val profileId = creator.profileId
        if (creator.source != sourceKey || !site.publicProfiles || profileId == null) return@sourceCall null
        val html = request(
            url = "${site.siteUrl}/index.php",
            query = mapOf("page" to "account", "s" to "profile", "id" to profileId),
            scanBody = false,
        )
        parseGelbooruAccountProfile(html, site.siteUrl)
    }

    override suspend fun accountPosts(creator: CreatorProfile, feed: AccountFeed, pageToken: String?): Page<Post> = sourceCall {
        when (feed) {
            AccountFeed.UPLOADS -> searchCreatorPosts(creator, pageToken)
            AccountFeed.FAVORITES -> favoritePosts(creator, pageToken)
        }
    }

    override suspend fun trendingTags(limit: Int): List<TagSuggestion> = sourceCall {
        val items = requestTagList(
            "orderby" to "count",
            "order" to "desc",
            "limit" to limit.coerceIn(1, 50).toString(),
        )
        items.orEmpty().mapNotNull { parseTag(it, "trending") }
    }

    override suspend fun autocompleteTags(prefix: String, limit: Int): List<TagSuggestion> = sourceCall {
        val normalized = prefix.trim()
        if (normalized.isBlank()) return@sourceCall emptyList()
        site.autocompleteUrl?.let { url -> return@sourceCall autocompleteFrom(url, normalized, limit) }
        val items = requestTagList(
            "name_pattern" to "$normalized%",
            "orderby" to "count",
            "order" to "desc",
            "limit" to limit.coerceIn(1, 30).toString(),
        ) ?: return@sourceCall autocompleteFrom("${site.siteUrl}/autocomplete.php", normalized, limit)
        items.mapNotNull { parseTag(it, "tag") }
    }

    override suspend fun fetchTagCounts(tags: List<String>): Map<String, Int> = sourceCall {
        val normalizedTags = tags.map(::normalizeBooruTag).filter(String::isNotBlank).distinct()
        val countsByName = linkedMapOf<String, Int>()
        normalizedTags.chunked(TAG_COUNT_BATCH_SIZE).forEach { chunk ->
            val items = requestTagList("names" to chunk.joinToString(" "), "limit" to chunk.size.toString())
                ?: return@sourceCall autocompleteTagCounts(sourceKey, normalizedTags, ::autocompleteTags)
            items.forEach { raw ->
                val tag = parseTag(raw, "tag") ?: return@forEach
                tag.count?.let { count -> countsByName.putIfAbsent(tag.text, count) }
            }
        }
        countsByName
    }

    override fun nativeCommentAnchor(commentId: String): String? = "c$commentId".takeIf { site.commentAnchors }

    @Volatile
    private var commentsApiDisabled = false

    override suspend fun comments(post: Post, pageToken: String?, parent: PostComment?): CommentPage = sourceCall {
        if (post.id.source != sourceKey) return@sourceCall CommentPage(emptyList(), total = 0, nextPageToken = null)
        val postId = post.id.sourcePostId
        if (pageToken == null && !commentsApiDisabled) {
            val query = apiQuery("s" to "comment", "q" to "index", "post_id" to postId, json = false)
            val body = requestBody(site.apiUrl, query)
            parseGelbooruCommentsXml(body, site)?.let { comments ->
                return@sourceCall CommentPage(comments, total = comments.size, nextPageToken = null)
            }
            throwIfAuthFailure(body)
            commentsApiDisabled = true
        }
        val pid = pageToken?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val html = request(
            url = "${site.siteUrl}/index.php",
            query = buildMap {
                put("page", "post")
                put("s", "view")
                put("id", postId)
                if (pid > 0) put("pid", pid.toString())
            },
            scanBody = false,
        )
        val page = parseGelbooruCommentsHtml(html, site, currentPid = pid)
        CommentPage(
            comments = page.comments,
            total = if (pid == 0 && page.nextPid == null) page.comments.size else null,
            nextPageToken = page.nextPid?.toString(),
        )
    }

    @Volatile
    private var tagListIgnoresJson = false

    private suspend fun requestTagList(vararg entries: Pair<String, String>): List<JsonObject>? {
        if (tagListIgnoresJson) return null
        val body = requestApi("s" to "tag", "q" to "index", *entries)
        if (site.tagList == GelbooruTagList.DETECT && body.trimStart().startsWith('<')) {
            tagListIgnoresJson = true
            return null
        }
        return parseItems(body, TAG_ENVELOPE)
    }

    private suspend fun autocompleteFrom(url: String, prefix: String, limit: Int): List<TagSuggestion> =
        parseAutocompleteSuggestions(request(url, mapOf("q" to prefix)), gson, site.label).take(limit)

    override suspend fun resolvePost(id: PostId): Post? = sourceCall {
        if (id.source != sourceKey) return@sourceCall null
        val lookup = when (site.postLookup) {
            GelbooruPostLookup.ID_PARAMETER -> "id" to id.sourcePostId
            GelbooruPostLookup.ID_TAG -> "tags" to "id:${id.sourcePostId}"
        }
        val response = requestApi("s" to "post", "q" to "index", lookup, "limit" to "1")
        parseItems(response, POST_ENVELOPE).firstOrNull()?.let(::parsePost)
    }

    override suspend fun relatedPosts(seed: PostId, limit: Int): List<Post> = sourceCall {
        if (seed.source != sourceKey || limit <= 0) return@sourceCall emptyList()
        val html = request(
            url = "${site.siteUrl}/index.php",
            query = mapOf("page" to "post", "s" to "view", "id" to seed.sourcePostId),
        )
        val relatedIds = parseGelbooruRelatedPostIds(
            html = html,
            baseUrl = site.siteUrl,
            seedId = seed.sourcePostId,
            limit = limit.coerceAtMost(MAX_RELATED_POSTS),
        ) ?: throw SourceAdapterException(
            reason = SourceFailureReason.PARSE,
            message = "${site.label} related-post block was not found",
        )
        if (relatedIds.isEmpty()) return@sourceCall emptyList()

        resolvePosts(relatedIds, RELATED_HYDRATION_CONCURRENCY).postsOrThrow()
    }

    private suspend fun favoritePosts(creator: CreatorProfile, pageToken: String?): Page<Post> {
        val profileId = creator.profileId
        if (creator.source != sourceKey || !site.favoritesList || profileId == null) return canonicalPostPage(emptyList(), null)
        return searchPosts("fav:$profileId", pageToken)
    }

    override suspend fun findByFileHash(md5: String): Post? = sourceCall {
        searchPosts("md5:$md5", pageToken = null).items.firstOrNull()
    }

    private suspend fun searchPosts(tags: String, pageToken: String?): Page<Post> {
        val pageIndex = pageToken?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val response = requestApi(
            "s" to "post",
            "q" to "index",
            "pid" to pageIndex.toString(),
            "limit" to PAGE_SIZE.toString(),
            "tags" to tags,
        )
        if (response.trimStart().startsWith(DEPTH_REFUSAL_PREFIX)) {
            throw SourceAdapterException(
                reason = SourceFailureReason.UNKNOWN,
                message = "${site.label} won't load results past this point",
            )
        }
        val rawPosts = parseItems(response, POST_ENVELOPE)
        val next = if (rawPosts.size >= PAGE_SIZE) (pageIndex + 1).toString() else null
        return canonicalPostPage(items = rawPosts.mapNotNull(::parsePost), nextPageToken = next)
    }

    private suspend fun resolvePosts(ids: List<String>, concurrency: Int): List<Result<Post?>> =
        ids.mapConcurrent(concurrency) { id -> runCatchingPreservingCancellation { resolvePost(PostId(sourceKey, id)) } }

    private fun List<Result<Post?>>.postsOrThrow(): List<Post> {
        val posts = mapNotNull(Result<Post?>::getOrNull).distinctBy(Post::id)
        if (posts.isNotEmpty() || none(Result<Post?>::isFailure)) return posts
        throw firstNotNullOf { result -> result.exceptionOrNull() }
    }

    private suspend fun requestApi(vararg entries: Pair<String, String>): String =
        request(site.apiUrl, apiQuery(*entries))

    private suspend fun apiQuery(vararg entries: Pair<String, String>, json: Boolean = true): Map<String, String> {
        val saved = credentials.keysFor(site.credentials, site.label)
        return buildMap {
            put("page", "dapi")
            if (json) put("json", "1")
            putAll(entries)
            putAll(GelbooruSite.authQuery(saved))
        }
    }

    private suspend fun request(url: String, query: Map<String, String>, scanBody: Boolean = true): String {
        val body = requestBody(url, query)
        if (scanBody) throwIfAuthFailure(body)
        return body
    }

    private suspend fun requestBody(url: String, query: Map<String, String>): String =
        httpClient.getChecked(site.label, url, query, site.headers, authBlockedBody = ::looksLikeGelbooruAuthFailure)

    private fun throwIfAuthFailure(body: String) {
        if (looksLikeGelbooruAuthFailure(body)) {
            throw SourceAdapterException(
                reason = SourceFailureReason.AUTH_REQUIRED,
                message = "${site.label} credentials are missing or invalid",
            )
        }
    }

    private fun parseItems(body: String, envelopeKey: String): List<JsonObject> {
        if (body.isBlank()) return emptyList()
        val root = parseJsonElement(body = body, gson = gson, errorLabel = site.label)
        if (root.isJsonPrimitive) {
            throw SourceAdapterException(
                reason = SourceFailureReason.PARSE,
                message = "${site.label} returned malformed JSON",
            )
        }
        return root.objectList(envelopeKey)
    }

    private fun parsePost(raw: JsonObject): Post? {
        val id = raw.stringValue("id")?.trim()?.ifBlank { null } ?: return null
        val tags = raw.stringValue("tags")
            ?.split(' ')
            ?.map { decodeHtmlEntities(it).trim() }
            ?.filter(String::isNotBlank)
            .orEmpty()
        val fullUrl = normalizeGelbooruMediaUrl(raw.stringValue("file_url"))
        val sampleUrl = normalizeGelbooruMediaUrl(raw.stringValue("sample_url"))
            ?.takeUnless { raw.stringValue("sample")?.lowercase() in NO_SAMPLE_FLAGS }
        val previewUrl = normalizeGelbooruMediaUrl(raw.stringValue("preview_url")) ?: sampleUrl ?: fullUrl
        val fullMime = mimeFromUrlOrExt(fullUrl, raw.stringValue("file_ext"))
        val creator = parseCreator(raw)
        return Post(
            id = PostId(sourceKey, id),
            preview = booruPreview(previewUrl, fullMime),
            full = fullUrl?.let { booruFull(it, fullMime, sampleUrl) },
            pageUrl = "${site.siteUrl}/index.php?page=post&s=view&id=$id",
            width = raw.intValue("width"),
            height = raw.intValue("height"),
            canonicalTags = tags,
            rawTags = tags,
            authorName = creator?.displayName ?: raw.stringValue("author")?.trim()?.ifBlank { null },
            createdAtEpochMs = raw.longValue("created_at")?.times(1000L)
                ?: parseGelbooruDateTimeMs(raw.stringValue("created_at"))
                ?: raw.longValue("change")?.times(1000L),
            durationMs = parseDurationMs(raw),
            creatorProfile = creator?.takeIf { site.creatorPosts },
            commentHint = raw.gelbooruCommentHint(),
            contentHash = contentHashOrNull(raw.stringValue("md5") ?: raw.stringValue("hash")),
            originUrl = originUrlOrNull(raw.stringValue("source")),
        )
    }

    private fun parseCreator(raw: JsonObject): CreatorProfile? =
        site.creatorProfile(raw.stringValue("owner"), raw.stringValue("creator_id"))

    private fun compileTags(query: Query): String {
        val orGroup = site.orGroup
        val include = query.effectiveIncludeTermGroups.flatMap { group ->
            val alternatives = group.terms.map { normalizeBooruTag(it.value) }.filter(String::isNotBlank)
            if (orGroup == null || alternatives.size < 2) {
                alternatives
            } else {
                listOf(alternatives.joinToString(orGroup.separator, orGroup.prefix, orGroup.suffix))
            }
        }
        val exclude = query.excludeTags.map(::normalizeBooruTag).filter(String::isNotBlank).map { "-$it" }
        val sort = when (query.sort) {
            SortMode.NEWEST, SortMode.RANDOM -> site.newestSortTag.takeIf { include.isNotEmpty() || exclude.isNotEmpty() }
            SortMode.POPULAR, SortMode.TOP -> site.scoreSortTag
        }
        return (include + exclude + listOfNotNull(sort)).take(MAX_SEARCH_TAGS).joinToString(" ")
    }
}

fun normalizeGelbooruMediaUrl(rawUrl: String?): String? =
    httpsMediaUrl(rawUrl)?.replaceFirst(GELBOORU_NUMBERED_VIDEO_CDN_PREFIX, "https://gelbooru.com/")

internal fun parseAutocompleteSuggestions(body: String, gson: Gson, siteLabel: String): List<TagSuggestion> =
    parseJsonArray(body, gson, "$siteLabel autocomplete").mapNotNull { item ->
        val obj = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        val text = obj.stringValue("value")?.let(::decodeHtmlEntities)?.trim().orEmpty()
        if (text.isBlank()) return@mapNotNull null
        TagSuggestion(text = text, type = "tag", count = parseTrailingCount(obj.get("label").asStringOrNull()))
    }

private fun parseTrailingCount(label: String?): Int? {
    val match = TRAILING_COUNT_REGEX.find(label.orEmpty()) ?: return null
    return match.groupValues.getOrNull(1)?.replace(",", "")?.toIntOrNull()
}

private fun parseTag(raw: JsonObject, type: String): TagSuggestion? {
    val name = raw.stringValue("name")?.let(::decodeHtmlEntities)?.trim().orEmpty()
    if (name.isBlank()) return null
    return TagSuggestion(text = name, type = type, count = raw.intValue("count") ?: raw.intValue("post_count"))
}

private fun parseDurationMs(raw: JsonObject): Long? = firstDurationMs(
    raw.durationFieldMs("duration_ms", multiplier = 1L),
    raw.durationFieldMs("durationMs", multiplier = 1L),
    raw.durationFieldMs("duration_seconds", multiplier = 1_000L),
    raw.durationFieldMs("durationSeconds", multiplier = 1_000L),
    raw.ambiguousDurationFieldMs("duration"),
    raw.ambiguousDurationFieldMs("video_duration"),
    raw.durationFieldMs("length_seconds", multiplier = 1_000L),
    raw.ambiguousDurationFieldMs("length"),
)

internal fun looksLikeGelbooruAuthFailure(body: String): Boolean {
    val lowered = body.lowercase()
    return "access denied" in lowered ||
        ("api key" in lowered && "required" in lowered) ||
        "missing authentication" in lowered ||
        "invalid api key" in lowered ||
        "invalid user" in lowered
}

private const val PAGE_SIZE = 40
private const val MAX_SEARCH_TAGS = 40
private const val TAG_COUNT_BATCH_SIZE = 50
internal const val MAX_RELATED_POSTS = 6
private const val RELATED_HYDRATION_CONCURRENCY = 2
private const val POST_ENVELOPE = "post"
private const val DEPTH_REFUSAL_PREFIX = "Too deep!"
private const val TAG_ENVELOPE = "tag"
private val NO_SAMPLE_FLAGS = setOf("false", "0")
private val GELBOORU_NUMBERED_VIDEO_CDN_PREFIX =
    Regex("^https://video-cdn\\d+\\.gelbooru\\.com/", RegexOption.IGNORE_CASE)
private val TRAILING_COUNT_REGEX = Regex("\\(([-\\d,]+)\\)\\s*$")
