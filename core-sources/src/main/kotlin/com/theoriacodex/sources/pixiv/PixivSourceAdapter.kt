package com.theoriacodex.sources.pixiv

import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.adapter.AnimatedSearchTerm
import com.google.gson.Gson
import com.theoriacodex.domain.adapter.AccountProfileSourceAdapter
import com.theoriacodex.domain.adapter.CommentsSourceAdapter
import com.theoriacodex.domain.adapter.CreatorPostsSourceAdapter
import com.theoriacodex.domain.adapter.DurationMetadataSourceAdapter
import com.theoriacodex.domain.adapter.DurationMetadataSourceResult
import com.theoriacodex.domain.adapter.Page
import com.theoriacodex.domain.adapter.MAX_RELATED_POST_CANDIDATES
import com.theoriacodex.domain.adapter.RelatedPostsSourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceCapabilities
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.adapter.TagSuggestion
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
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.tags.pixivSearchToken
import com.theoriacodex.sources.common.canonicalPostPage
import com.theoriacodex.sources.common.classifyHttpFailure
import com.theoriacodex.sources.common.isSuccessful
import com.theoriacodex.sources.common.optionalJsonObject
import com.theoriacodex.sources.common.parseJsonObject
import com.theoriacodex.sources.common.sourceCall
import com.theoriacodex.sources.common.sourceNetworkFailure
import com.theoriacodex.sources.common.stringValue
import com.theoriacodex.sources.credentials.PixivAuthTokens
import com.theoriacodex.sources.credentials.SourceCredentialsProvider
import com.theoriacodex.sources.http.RequestPacer
import com.theoriacodex.sources.http.SourceHttpClient
import com.theoriacodex.sources.http.SourceHttpResponse
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import com.theoriacodex.sources.common.objects

class PixivSourceAdapter(
    private val httpClient: SourceHttpClient,
    credentialsProvider: SourceCredentialsProvider,
    authApi: PixivAuthApi = PixivAuthApi(httpClient),
    private val tokenCoordinator: PixivTokenCoordinator = PixivTokenCoordinator(
        credentialsProvider = credentialsProvider,
        authApi = authApi,
    ),
    private val gson: Gson = Gson(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val minRequestIntervalMs: Long = 350L,
    private val ugoiraMetadataCache: PixivUgoiraMetadataCache = PixivUgoiraMetadataCache(),
    private val callContext: CoroutineContext = Dispatchers.Default,
) : SourceAdapter, CreatorPostsSourceAdapter, RelatedPostsSourceAdapter, DurationMetadataSourceAdapter,
    CommentsSourceAdapter, AccountProfileSourceAdapter {
    override val sourceKey: SourceKey = SourceKey.PIXIV

    override val capabilities: SourceCapabilities = SourceCapabilities(
        supportsSortNewest = true,
        supportsSortPopular = true,
        supportsSortTop = true,
        supportsSortRandom = true,
        supportsExcludeTagsServerSide = false,
        animatedTerm = AnimatedSearchTerm(SearchTerm("うごイラ")),
    )

    private val browsePacer = RequestPacer(minRequestIntervalMs, clock)

    private val durationPacer = RequestPacer(minRequestIntervalMs, clock)

    override suspend fun search(query: Query, pageToken: String?): Page<Post> = sourceCall(callContext) {
        val params = offsetPageParams(
            pageToken,
            "word" to compileTagQuery(query),
            "search_target" to "partial_match_for_tags",
            "sort" to mapSort(query.sort),
            "filter" to "for_android",
        )
        query.dateRange?.boundsAt(clock())?.let { range ->
            range.fromEpochMs?.let { params["start_date"] = formatEpochDate(it) }
            range.toEpochMs?.let { params["end_date"] = formatEpochDate(it) }
        }

        val illusts = fetchIllusts("$PIXIV_API_BASE/v1/search/illust", params)
        canonicalPostPage(
            items = if (query.sort == SortMode.RANDOM) illusts.posts.shuffled() else illusts.posts,
            nextPageToken = parseNextOffset(illusts.nextUrl),
        )
    }

    override suspend fun trendingTags(limit: Int): List<TagSuggestion> = sourceCall(callContext) {
        val response = authorizedGet(
            url = "$PIXIV_API_BASE/v1/trending-tags/illust",
            query = mapOf("filter" to "for_android"),
        )
        val root = parseJsonObject(response, gson, "Pixiv")
        root.objects("trend_tags").mapNotNull { item ->
            item.optionalJsonObject("tag")?.let { tag -> parsePixivTagSuggestion(tag, type = "trending") }
        }.take(limit)
    }

    override suspend fun autocompleteTags(prefix: String, limit: Int): List<TagSuggestion> = sourceCall(callContext) {
        if (prefix.isBlank()) return@sourceCall emptyList()
        val response = authorizedGet(
            url = "$PIXIV_API_BASE/v2/search/autocomplete",
            query = mapOf("word" to prefix),
        )
        val root = parseJsonObject(response, gson, "Pixiv")
        root.objects("tags").mapNotNull { tag -> parsePixivTagSuggestion(tag, type = "tag") }.take(limit)
    }

    override suspend fun resolvePost(id: PostId): Post? = sourceCall(callContext) {
        if (id.source != SourceKey.PIXIV) return@sourceCall null
        val response = authorizedGet(
            url = "$PIXIV_API_BASE/v1/illust/detail",
            query = mapOf("illust_id" to id.sourcePostId),
        )
        val root = parseJsonObject(response, gson, "Pixiv")
        val illust = root.optionalJsonObject("illust") ?: return@sourceCall null
        val parsed = parsePixivIllust(illust) ?: return@sourceCall null
        if (illust.stringValue("type") == "ugoira" && parsed.durationMs == null) {
            parsed.copy(durationMs = fetchUgoiraDurationMs(id.sourcePostId))
        } else {
            parsed
        }
    }

    override suspend fun relatedPosts(seed: PostId, limit: Int): List<Post> = sourceCall(callContext) {
        if (seed.source != SourceKey.PIXIV || limit <= 0) return@sourceCall emptyList()
        fetchIllusts(
            url = "$PIXIV_API_BASE/v2/illust/related",
            query = mapOf("illust_id" to seed.sourcePostId, "filter" to "for_android"),
            label = "Pixiv related illustrations",
        ).posts
            .asSequence()
            .filterNot { post -> post.id == seed }
            .distinctBy(Post::id)
            .take(limit.coerceAtMost(MAX_RELATED_POST_CANDIDATES))
            .toList()
    }

    override suspend fun searchCreatorPosts(
        creator: CreatorProfile,
        pageToken: String?,
    ): Page<Post> = sourceCall(callContext) {
        if (creator.source != SourceKey.PIXIV) return@sourceCall canonicalPostPage(items = emptyList(), nextPageToken = null)
        val userId = creator.uploadsQuery?.trim().takeIf { !it.isNullOrBlank() }
            ?: creator.profileId?.trim().takeIf { !it.isNullOrBlank() }
            ?: return@sourceCall canonicalPostPage(items = emptyList(), nextPageToken = null)
        val illusts = fetchIllusts(
            url = "$PIXIV_API_BASE/v1/user/illusts",
            query = offsetPageParams(pageToken, "user_id" to userId, "type" to "illust", "filter" to "for_android"),
        )
        canonicalPostPage(items = illusts.posts, nextPageToken = parseNextOffset(illusts.nextUrl))
    }

    override fun accountFeeds(creator: CreatorProfile): List<AccountFeed> =
        if (accountUserId(creator) == null) emptyList() else listOf(AccountFeed.UPLOADS, AccountFeed.FAVORITES)

    override suspend fun accountDetails(creator: CreatorProfile): AccountDetails? = sourceCall(callContext) {
        val userId = accountUserId(creator) ?: return@sourceCall null
        val response = authorizedGet(
            url = "$PIXIV_API_BASE/v1/user/detail",
            query = mapOf("user_id" to userId, "filter" to "for_android"),
        )
        parsePixivAccountDetails(parseJsonObject(response, gson, "Pixiv"))
    }

    override suspend fun accountPosts(creator: CreatorProfile, feed: AccountFeed, pageToken: String?): Page<Post> = sourceCall(callContext) {
        val userId = accountUserId(creator) ?: return@sourceCall canonicalPostPage(items = emptyList(), nextPageToken = null)
        when (feed) {
            AccountFeed.UPLOADS -> searchCreatorPosts(creator, pageToken)
            AccountFeed.FAVORITES -> publicBookmarks(userId, pageToken)
        }
    }

    private fun accountUserId(creator: CreatorProfile): String? =
        creator.profileId?.trim()?.takeIf { creator.source == SourceKey.PIXIV && it.isNotBlank() }

    private suspend fun publicBookmarks(userId: String, pageToken: String?): Page<Post> {
        val illusts = if (pageToken == null) {
            fetchIllusts(
                url = "$PIXIV_API_BASE/v1/user/bookmarks/illust",
                query = mapOf("user_id" to userId, "restrict" to "public", "filter" to "for_android"),
            )
        } else {
            fetchIllusts(trustedPixivLink(pageToken), emptyMap())
        }
        return canonicalPostPage(items = illusts.posts, nextPageToken = illusts.nextUrl?.trim()?.takeIf(String::isNotBlank))
    }

    private suspend fun fetchIllusts(url: String, query: Map<String, String>, label: String = "Pixiv"): PixivIllusts {
        val root = parseJsonObject(authorizedGet(url, query), gson, label)
        return PixivIllusts(posts = root.pixivIllusts(), nextUrl = root.stringValue("next_url"))
    }

    private fun trustedPixivLink(url: String): String {
        if (!url.startsWith("$PIXIV_API_BASE/")) {
            throw SourceAdapterException(SourceFailureReason.PARSE, "Pixiv returned an unexpected next-page link")
        }
        return url
    }

    override suspend fun comments(post: Post, pageToken: String?, parent: PostComment?): CommentPage = sourceCall(callContext) {
        if (post.id.source != SourceKey.PIXIV) return@sourceCall CommentPage(emptyList(), total = 0, nextPageToken = null)
        pageToken?.let { nextUrl ->
            return@sourceCall parsePixivCommentPage(
                parseJsonObject(authorizedGet(trustedPixivLink(nextUrl), emptyMap()), gson, "Pixiv"),
                total = null,
            )
        }
        if (parent != null) {
            val replies = authorizedGet(
                url = "$PIXIV_API_BASE/v2/illust/comment/replies",
                query = mapOf("comment_id" to parent.id),
            )
            return@sourceCall parsePixivCommentPage(parseJsonObject(replies, gson, "Pixiv"), total = null)
        }
        coroutineScope {
            val total = async {
                runCatchingPreservingCancellation {
                    val detail = authorizedGet(
                        url = "$PIXIV_API_BASE/v1/illust/detail",
                        query = mapOf("illust_id" to post.id.sourcePostId),
                    )
                    parsePixivCommentTotal(parseJsonObject(detail, gson, "Pixiv"))
                }.getOrNull()
            }
            val first = authorizedGet(
                url = "$PIXIV_API_BASE/v3/illust/comments",
                query = mapOf("illust_id" to post.id.sourcePostId),
            )
            parsePixivCommentPage(parseJsonObject(first, gson, "Pixiv"), total = total.await())
        }
    }

    private suspend fun authorizedGet(
        url: String,
        query: Map<String, String>,
        pacer: RequestPacer = browsePacer,
    ): String {
        pacer.awaitTurn()
        var tokens = tokenCoordinator.activeTokens()
        var response = authorizedAttempt(url, query, tokens, failureLabel = "Pixiv network")
        if (response.statusCode == 401 || response.statusCode == 403) {
            tokens = tokenCoordinator.refreshAfterAuthFailure(tokens)
            response = authorizedAttempt(url, query, tokens, failureLabel = "Pixiv retry")
        }
        if (!response.isSuccessful()) {
            throw SourceAdapterException(
                reason = mapHttpFailure(response.statusCode),
                message = "Pixiv request failed (${response.statusCode})",
            )
        }
        return response.body
    }

    private suspend fun authorizedAttempt(
        url: String,
        query: Map<String, String>,
        tokens: PixivAuthTokens,
        failureLabel: String,
    ): SourceHttpResponse = try {
        httpClient.get(url = url, query = query, headers = authorizedHeaders(tokens.accessToken))
    } catch (error: IOException) {
        sourceNetworkFailure(failureLabel, error)
    }

    private fun authorizedHeaders(accessToken: String): Map<String, String> = mapOf(
        "Authorization" to "Bearer $accessToken",
        "Accept-Language" to PIXIV_TRANSLATION_LANGUAGE,
    )

    override suspend fun resolveDurationMetadata(post: Post): DurationMetadataSourceResult = sourceCall(callContext) {
        val isUgoira = post.id.source == SourceKey.PIXIV &&
            (post.full?.mime == PIXIV_UGOIRA_MIME || post.media.any { it.mime == PIXIV_UGOIRA_MIME })
        if (!isUgoira) return@sourceCall DurationMetadataSourceResult.Unsupported
        fetchUgoiraDurationMs(post.id.sourcePostId)
            ?.let(DurationMetadataSourceResult::Known)
            ?: DurationMetadataSourceResult.Unsupported
    }

    private suspend fun fetchUgoiraDurationMs(postId: String): Long? {
        val cached = ugoiraMetadataCache.get(postId)
        val body = cached ?: authorizedGet(
            url = "$PIXIV_API_BASE/v1/ugoira/metadata",
            query = mapOf("illust_id" to postId),
            pacer = durationPacer,
        )
        val durationMs = parsePixivUgoiraDurationMs(parseJsonObject(body, gson, "Pixiv"))
        if (cached == null) ugoiraMetadataCache.put(postId, body)
        return durationMs
    }

    private fun mapSort(sortMode: SortMode): String {
        return when (sortMode) {
            SortMode.NEWEST -> "date_desc"
            SortMode.POPULAR -> "popular_desc"
            SortMode.TOP -> "popular_desc"
            SortMode.RANDOM -> "date_desc"
        }
    }
}

private class PixivIllusts(val posts: List<Post>, val nextUrl: String?)

private fun offsetPageParams(pageToken: String?, vararg params: Pair<String, String>): MutableMap<String, String> =
    linkedMapOf(*params).apply {
        val offset = pageToken?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        if (offset > 0) put("offset", offset.toString())
    }

private fun parseNextOffset(nextUrl: String?): String? {
    if (nextUrl.isNullOrBlank()) return null
    val match = Regex("[?&]offset=(\\d+)").find(nextUrl) ?: return null
    return match.groupValues.getOrNull(1)
}

private fun mapHttpFailure(statusCode: Int): SourceFailureReason {
    return classifyHttpFailure(
        statusCode = statusCode,
        unauthorizedReason = SourceFailureReason.AUTH_EXPIRED,
        forbiddenReason = SourceFailureReason.AUTH_REQUIRED,
    )
}

private fun compileTagQuery(query: Query): String {
    val include = query.includeTags
        .map(::pixivSearchToken)
        .filter { it.isNotBlank() }
    val exclude = query.excludeTags
        .map(::pixivSearchToken)
        .filter { it.isNotBlank() }
        .map { "-$it" }
    return (include + exclude).joinToString(" ")
}

private fun formatEpochDate(value: Long): String {
    return DateTimeFormatter.ofPattern("yyyy-MM-dd")
        .withZone(ZoneOffset.UTC)
        .format(Instant.ofEpochMilli(value))
}

private const val PIXIV_API_BASE: String = "https://app-api.pixiv.net"
private const val PIXIV_TRANSLATION_LANGUAGE: String = "en-US"
const val PIXIV_UGOIRA_MIME: String = com.theoriacodex.domain.model.PIXIV_UGOIRA_MIME
