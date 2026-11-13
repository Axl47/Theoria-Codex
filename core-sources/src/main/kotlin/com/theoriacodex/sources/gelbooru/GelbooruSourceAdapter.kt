package com.theoriacodex.sources.gelbooru

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.theoriacodex.domain.adapter.AccountProfileSourceAdapter
import com.theoriacodex.domain.adapter.CommentsSourceAdapter
import com.theoriacodex.domain.adapter.CreatorPostsSourceAdapter
import com.theoriacodex.domain.adapter.Page
import com.theoriacodex.domain.adapter.QuickQueryKind
import com.theoriacodex.domain.adapter.RelatedPostsSourceAdapter
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
import com.theoriacodex.domain.model.ImageRef
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
import com.theoriacodex.sources.common.canonicalPostPage
import com.theoriacodex.sources.common.classifyHttpFailure
import com.theoriacodex.sources.common.durationFieldMs
import com.theoriacodex.sources.common.firstDurationMs
import com.theoriacodex.sources.common.httpsMediaUrl
import com.theoriacodex.sources.common.intValue
import com.theoriacodex.sources.common.isSuccessful
import com.theoriacodex.sources.common.longValue
import com.theoriacodex.sources.common.mimeFromUrlOrExt
import com.theoriacodex.sources.common.normalizeBooruTag
import com.theoriacodex.sources.common.objectList
import com.theoriacodex.sources.common.parseJsonArray
import com.theoriacodex.sources.common.parseJsonElement
import com.theoriacodex.sources.common.sourceNetworkFailure
import com.theoriacodex.sources.common.sourceQuickQuery
import com.theoriacodex.sources.common.stringValue
import com.theoriacodex.sources.credentials.ApiKeyUse
import com.theoriacodex.sources.credentials.UserApiKeyCredentialLookup
import com.theoriacodex.sources.http.SourceHttpClient
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Builds the adapter for any [GelbooruSite]. The app discovers creator and related posts with `is`
 * checks, so the returned adapter implements those interfaces only when the profile enables them.
 * Named like a class so registration reads the same as the Danbooru family.
 */
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

/**
 * What every Gelbooru-family site supports; creator and related posts depend on the profile. Every
 * creator is a site account (post uploaders and commenters alike), so account pages are universal.
 */
interface GelbooruFamilySourceAdapter :
    SourceAdapter, TagCountLookupSourceAdapter, CommentsSourceAdapter, AccountProfileSourceAdapter

/** Speaks the DAPI for one [GelbooruSite]; every search pages by `pid` in fixed 40-post pages. */
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
        supportsDateRangeServerSide = false,
        supportsMinScoreServerSide = false,
        requiresCredentials = site.credentials == ApiKeyUse.REQUIRED,
        supportsGroupedIncludeTagsServerSide = site.orGroup != null,
    )

    override suspend fun search(query: Query, pageToken: String?): Page<Post> =
        searchPosts(compileTags(query), pageToken)

    override suspend fun searchCreatorPosts(creator: CreatorProfile, pageToken: String?): Page<Post> {
        val uploadsQuery = creator.uploadsQuery?.trim()
        if (creator.source != sourceKey || uploadsQuery.isNullOrBlank()) return canonicalPostPage(emptyList(), null)
        return searchPosts(uploadsQuery, pageToken)
    }

    override fun accountFeeds(creator: CreatorProfile): List<AccountFeed> {
        if (creator.source != sourceKey) return emptyList()
        return listOfNotNull(
            AccountFeed.UPLOADS.takeIf { !creator.uploadsQuery.isNullOrBlank() },
            AccountFeed.FAVORITES.takeIf { site.favoritesList && creator.profileId != null },
        )
    }

    /** Statistics, avatar and bio from the profile page; null on sites without public profiles. */
    override suspend fun accountDetails(creator: CreatorProfile): AccountDetails? {
        val profileId = creator.profileId
        if (creator.source != sourceKey || !site.publicProfiles || profileId == null) return null
        val html = request(
            url = "${site.siteUrl}/index.php",
            query = mapOf("page" to "account", "s" to "profile", "id" to profileId),
            scanBody = false,
        )
        return parseGelbooruAccountProfile(html, site.siteUrl)
    }

    override suspend fun accountPosts(creator: CreatorProfile, feed: AccountFeed, pageToken: String?): Page<Post> =
        when (feed) {
            AccountFeed.UPLOADS -> searchCreatorPosts(creator, pageToken)
            AccountFeed.FAVORITES -> favoritePosts(creator, pageToken)
        }

    override suspend fun trendingTags(limit: Int): List<TagSuggestion> {
        val items = requestTagList(
            "orderby" to "count",
            "order" to "desc",
            "limit" to limit.coerceIn(1, 50).toString(),
        )
        return items.orEmpty().mapNotNull { parseTag(it, "trending") }
    }

    override suspend fun autocompleteTags(prefix: String, limit: Int): List<TagSuggestion> {
        val normalized = prefix.trim()
        if (normalized.isBlank()) return emptyList()
        site.autocompleteUrl?.let { url -> return autocompleteFrom(url, normalized, limit) }
        val items = requestTagList(
            "name_pattern" to "$normalized%",
            "orderby" to "count",
            "order" to "desc",
            "limit" to limit.coerceIn(1, 30).toString(),
        ) ?: return autocompleteFrom("${site.siteUrl}/autocomplete.php", normalized, limit)
        return items.mapNotNull { parseTag(it, "tag") }
    }

    override suspend fun fetchTagCounts(tags: List<String>): Map<String, Int> {
        val normalizedTags = tags.map(::normalizeBooruTag).filter(String::isNotBlank).distinct()
        val countsByName = linkedMapOf<String, Int>()
        normalizedTags.chunked(TAG_COUNT_BATCH_SIZE).forEach { chunk ->
            val items = requestTagList("names" to chunk.joinToString(" "), "limit" to chunk.size.toString())
                ?: return autocompleteTagCounts(sourceKey, normalizedTags, ::autocompleteTags)
            items.forEach { raw ->
                val tag = parseTag(raw, "tag") ?: return@forEach
                tag.count?.let { count -> countsByName.putIfAbsent(tag.text, count) }
            }
        }
        return countsByName
    }

    override fun nativeCommentAnchor(commentId: String): String? = "c$commentId".takeIf { site.commentAnchors }

    /** Set once the DAPI answers comment requests with something other than comment XML; never reset. */
    @Volatile
    private var commentsApiDisabled = false

    /**
     * Comments come from the DAPI when it answers comment XML, which has no paging. Sites that disable
     * it (gelbooru.com) are read from the post page instead, which pages by `pid` offsets; those tokens
     * are never DAPI tokens, so any page token goes straight to the post page. [parent] is ignored:
     * these sites have no replies.
     */
    override suspend fun comments(post: Post, pageToken: String?, parent: PostComment?): CommentPage {
        if (post.id.source != sourceKey) return CommentPage(emptyList(), total = 0, nextPageToken = null)
        val postId = post.id.sourcePostId
        if (pageToken == null && !commentsApiDisabled) {
            val query = apiQuery("s" to "comment", "q" to "index", "post_id" to postId, json = false)
            val body = requestBody(site.apiUrl, query)
            parseGelbooruCommentsXml(body, site)?.let { comments ->
                return CommentPage(comments, total = comments.size, nextPageToken = null)
            }
            // Comment text can mention keys, so the key-failure scan runs only on answers that are not comments.
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
        return CommentPage(
            comments = page.comments,
            total = if (pid == 0 && page.nextPid == null) page.comments.size else null,
            nextPageToken = page.nextPid?.toString(),
        )
    }

    /** Set once a [GelbooruTagList.DETECT] site answers the tag list in XML; never reset. */
    @Volatile
    private var tagListIgnoresJson = false

    /** Tag-list rows, or null when this site's tag list ignores `json=1` (see [GelbooruTagList.DETECT]). */
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

    override suspend fun quickQuery(kind: QuickQueryKind): Query = sourceQuickQuery(source = sourceKey, kind = kind)

    override suspend fun resolvePost(id: PostId): Post? {
        if (id.source != sourceKey) return null
        val lookup = when (site.postLookup) {
            GelbooruPostLookup.ID_PARAMETER -> "id" to id.sourcePostId
            GelbooruPostLookup.ID_TAG -> "tags" to "id:${id.sourcePostId}"
        }
        val response = requestApi("s" to "post", "q" to "index", lookup, "limit" to "1")
        return parseItems(response, POST_ENVELOPE).firstOrNull()?.let(::parsePost)
    }

    override suspend fun relatedPosts(seed: PostId, limit: Int): List<Post> {
        if (seed.source != sourceKey || limit <= 0) return emptyList()
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
        if (relatedIds.isEmpty()) return emptyList()

        return resolvePosts(relatedIds, RELATED_HYDRATION_CONCURRENCY).postsOrThrow()
    }

    /** A page of the account's public favorites: the DAPI answers `fav:<id>` in the same order as the site's favorites list. */
    private suspend fun favoritePosts(creator: CreatorProfile, pageToken: String?): Page<Post> {
        val profileId = creator.profileId
        if (creator.source != sourceKey || !site.favoritesList || profileId == null) return canonicalPostPage(emptyList(), null)
        return searchPosts("fav:$profileId", pageToken)
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
        val rawPosts = parseItems(response, POST_ENVELOPE)
        // Fullness counts every raw record, so a malformed entry never ends paging early.
        val next = if (rawPosts.size >= PAGE_SIZE) (pageIndex + 1).toString() else null
        return canonicalPostPage(items = rawPosts.mapNotNull(::parsePost), nextPageToken = next)
    }

    /** Resolves [ids] through the DAPI, at most [concurrency] at a time, keeping their order; each failure stays its own result. */
    private suspend fun resolvePosts(ids: List<String>, concurrency: Int): List<Result<Post?>> = supervisorScope {
        val semaphore = Semaphore(concurrency)
        ids.map { id ->
            async {
                semaphore.withPermit {
                    try {
                        Result.success(resolvePost(PostId(sourceKey, id)))
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        Result.failure(error)
                    }
                }
            }
        }.awaitAll()
    }

    /** The resolved posts; partial failures drop their posts, but a batch where nothing resolved and something failed rethrows. */
    private fun List<Result<Post?>>.postsOrThrow(): List<Post> {
        val posts = mapNotNull(Result<Post?>::getOrNull).distinctBy(Post::id)
        if (posts.isNotEmpty() || none(Result<Post?>::isFailure)) return posts
        throw firstNotNullOf { result -> result.exceptionOrNull() }
    }

    /** DAPI call with the site's credentials; a site that requires them fails before the request. */
    private suspend fun requestApi(vararg entries: Pair<String, String>): String =
        request(site.apiUrl, apiQuery(*entries))

    /** DAPI query with the site's credentials. Comments are XML only, so they ask for no `json=1`. */
    private suspend fun apiQuery(vararg entries: Pair<String, String>, json: Boolean = true): Map<String, String> {
        val saved = credentials()
        if (saved == null && site.credentials == ApiKeyUse.REQUIRED) {
            throw SourceAdapterException(
                reason = SourceFailureReason.AUTH_REQUIRED,
                message = "${site.label} credentials required",
            )
        }
        return buildMap {
            put("page", "dapi")
            if (json) put("json", "1")
            putAll(entries)
            saved?.let {
                put("user_id", it.userId)
                put("api_key", it.apiKey)
            }
        }
    }

    /** [scanBody] is off for pages that show user comments, whose text could mimic a key failure. */
    private suspend fun request(url: String, query: Map<String, String>, scanBody: Boolean = true): String {
        val body = requestBody(url, query)
        if (scanBody) throwIfAuthFailure(body)
        return body
    }

    private suspend fun requestBody(url: String, query: Map<String, String>): String {
        val response = try {
            httpClient.get(url = url, query = query, headers = site.headers)
        } catch (error: IOException) {
            sourceNetworkFailure(site.label, error)
        }
        if (!response.isSuccessful()) {
            throw SourceAdapterException(
                reason = classifyHttpFailure(
                    statusCode = response.statusCode,
                    body = response.body,
                    authBlockedBody = ::looksLikeGelbooruAuthFailure,
                ),
                message = "${site.label} request failed (${response.statusCode})",
            )
        }
        return response.body
    }

    /** Sites report a missing or bad key in a successful response body. */
    private fun throwIfAuthFailure(body: String) {
        if (looksLikeGelbooruAuthFailure(body)) {
            throw SourceAdapterException(
                reason = SourceFailureReason.AUTH_REQUIRED,
                message = "${site.label} credentials are missing or invalid",
            )
        }
    }

    /** Some sites answer an empty result with an empty body instead of `[]`. */
    private fun parseItems(body: String, envelopeKey: String): List<JsonObject> {
        if (body.isBlank()) return emptyList()
        return parseJsonElement(body = body, gson = gson, errorLabel = site.label).objectList(envelopeKey)
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
            // Forks that report `sample: false` may still fill `sample_url` with a path that was never generated.
            ?.takeUnless { raw.stringValue("sample")?.lowercase() in NO_SAMPLE_FLAGS }
        val previewUrl = normalizeGelbooruMediaUrl(raw.stringValue("preview_url")) ?: sampleUrl ?: fullUrl
        val fullMime = mimeFromUrlOrExt(fullUrl, raw.stringValue("file_ext"))
        val creator = parseCreator(raw)
        return Post(
            id = PostId(sourceKey, id),
            preview = ImageRef(url = previewUrl, localPath = null, mime = mimeFromUrlOrExt(previewUrl, null) ?: fullMime),
            full = fullUrl?.let { url ->
                ImageRef(
                    url = url,
                    localPath = null,
                    mime = fullMime,
                    // A distinct sample is a lighter rendition on every fork, so it loads before the original.
                    progressiveUrls = listOfNotNull(sampleUrl).filter { it != url },
                )
            },
            pageUrl = "${site.siteUrl}/index.php?page=post&s=view&id=$id",
            width = raw.intValue("width"),
            height = raw.intValue("height"),
            canonicalTags = tags,
            rawTags = tags,
            authorName = creator?.displayName ?: raw.stringValue("author")?.trim()?.ifBlank { null },
            // `created_at` is a date string on most sites; `change` is the epoch-second fallback.
            createdAtEpochMs = (raw.longValue("created_at") ?: raw.longValue("change"))?.times(1000L),
            durationMs = parseDurationMs(raw),
            creatorProfile = creator?.takeIf { site.creatorPosts },
            commentHint = raw.gelbooruCommentHint(),
            // gelbooru.com names the file hash `md5`; Gelbooru 0.2 forks name it `hash`.
            contentHash = contentHashOrNull(raw.stringValue("md5") ?: raw.stringValue("hash")),
        )
    }

    /** The uploader is named by `owner`, or by `creator_id` alone on posts without one. */
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
            // With no other tags the site already lists newest first, and asking again costs about 7 s per page.
            SortMode.NEWEST, SortMode.RANDOM -> site.newestSortTag.takeIf { include.isNotEmpty() || exclude.isNotEmpty() }
            SortMode.POPULAR, SortMode.TOP -> site.scoreSortTag
        }
        return (include + exclude + listOfNotNull(sort)).take(MAX_SEARCH_TAGS).joinToString(" ")
    }
}

/**
 * Media URLs load over HTTPS only. Gelbooru's numbered video CDN hosts serve invalid certificates;
 * the main host serves the same paths.
 */
fun normalizeGelbooruMediaUrl(rawUrl: String?): String? =
    httpsMediaUrl(rawUrl)?.replaceFirst(GELBOORU_NUMBERED_VIDEO_CDN_PREFIX, "https://gelbooru.com/")

/** Parses `autocomplete.php` rows such as `{"label": "landscape (9927)", "value": "landscape"}`. */
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

/** Missing-key replies from Gelbooru (`API key required`, `Access denied`) and rule34.xxx. */
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
private const val MAX_RELATED_POSTS = 6
private const val RELATED_HYDRATION_CONCURRENCY = 2
private const val POST_ENVELOPE = "post"
private const val TAG_ENVELOPE = "tag"
private val NO_SAMPLE_FLAGS = setOf("false", "0")
private val GELBOORU_NUMBERED_VIDEO_CDN_PREFIX =
    Regex("^https://video-cdn\\d+\\.gelbooru\\.com/", RegexOption.IGNORE_CASE)
private val TRAILING_COUNT_REGEX = Regex("\\(([-\\d,]+)\\)\\s*$")
