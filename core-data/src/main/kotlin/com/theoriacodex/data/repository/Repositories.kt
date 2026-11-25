package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.Codex
import com.theoriacodex.domain.model.CodexAutomaticTag
import com.theoriacodex.domain.model.CodexItem
import com.theoriacodex.domain.model.DirectedSort
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SortChoice
import com.theoriacodex.domain.model.SortDirection
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

interface CodexRepository {
    fun observeCodices(): Flow<List<Codex>>
    fun observeCodex(codexId: String): Flow<Codex?>
    suspend fun ensureCodex(codexId: String, name: String): Codex
    suspend fun createCodex(name: String): Codex
    suspend fun renameCodex(codexId: String, name: String)
    suspend fun setAutomaticTag(codexId: String, tag: CodexAutomaticTag, enabled: Boolean)
    suspend fun deleteCodexForUndo(codexId: String): DeletedCodexSnapshot?
    suspend fun restoreDeletedCodex(snapshot: DeletedCodexSnapshot)
    suspend fun deleteCodexIfEmpty(codexId: String): Boolean
    fun observeCodexItems(codexId: String): Flow<List<CodexItem>>
    fun observeCodexPosts(codexId: String, sort: CodexSortMode): Flow<List<Post>>
    fun observeLibraryPosts(codexIds: Set<String>): Flow<List<Post>> = if (codexIds.isEmpty()) flowOf(emptyList()) else
        combine(codexIds.sorted().map { observeCodexPosts(it, CodexSortMode.NEWEST_SAVED) }) { lists ->
            lists.flatMap { it }.distinctBy(Post::id)
        }
    suspend fun getPost(postId: com.theoriacodex.domain.model.PostId): Post?
    suspend fun addItems(codexId: String, posts: List<Post>): Int
    suspend fun addItem(codexId: String, post: Post) {
        addItems(codexId, listOf(post))
    }
    fun observeCodexSummaries(codexIds: Set<String>, coverLimit: Int = 8): Flow<List<CodexSummary>>
    fun observeSavedPostIds(codexIds: Set<String>): Flow<Set<PostId>>
    suspend fun updatePost(post: Post)
    suspend fun removeItems(codexId: String, postIds: Set<PostId>)
    suspend fun restoreItems(items: List<CodexItem>, posts: List<Post>)
}

data class DeletedCodexSnapshot(
    val codex: Codex,
    val position: Int,
    val items: List<CodexItem>,
    val posts: List<Post>,
)

data class ClearedLikesSnapshot(
    val items: List<CodexItem>,
    val posts: List<Post>,
    val likes: List<LikedPost>,
)

data class CodexSummary(
    val codexId: String,
    val itemCount: Int,
    val coverPosts: List<Post>,
)

enum class CodexPostSortKey { DATE_ADDED, WATCH_TIME, OPENS, PAGES, LENGTH, CREATOR, SOURCE }

enum class CodexSortMode(key: CodexPostSortKey, direction: SortDirection?) : DirectedSort<CodexPostSortKey> {
    NEWEST_SAVED(CodexPostSortKey.DATE_ADDED, SortDirection.DESCENDING),
    OLDEST_SAVED(CodexPostSortKey.DATE_ADDED, SortDirection.ASCENDING),
    MOST_WATCHED(CodexPostSortKey.WATCH_TIME, SortDirection.DESCENDING),
    LEAST_WATCHED(CodexPostSortKey.WATCH_TIME, SortDirection.ASCENDING),
    MOST_OPENED(CodexPostSortKey.OPENS, SortDirection.DESCENDING),
    LEAST_OPENED(CodexPostSortKey.OPENS, SortDirection.ASCENDING),
    MOST_PAGES(CodexPostSortKey.PAGES, SortDirection.DESCENDING),
    FEWEST_PAGES(CodexPostSortKey.PAGES, SortDirection.ASCENDING),
    LONGEST(CodexPostSortKey.LENGTH, SortDirection.DESCENDING),
    SHORTEST(CodexPostSortKey.LENGTH, SortDirection.ASCENDING),
    CREATOR_ASCENDING(CodexPostSortKey.CREATOR, SortDirection.ASCENDING),
    CREATOR_DESCENDING(CodexPostSortKey.CREATOR, SortDirection.DESCENDING),
    BY_SOURCE(CodexPostSortKey.SOURCE, null),
    ;

    override val choice = SortChoice(key, direction)
}

interface QueryRepository {
    fun observeAppliedQuery(modeKey: String): Flow<Query?>
    suspend fun upsertAppliedQuery(modeKey: String, query: Query)
    suspend fun upsertAppliedQueries(queries: Map<String, Query>) {
        queries.forEach { (key, query) -> upsertAppliedQuery(key, query) }
    }
}

enum class RecentPostSection {
    WATCHED,
    CODEX,
    ;

    companion object {
        fun fromOrigin(origin: ViewerStreamSource): RecentPostSection {
            return when (origin) {
                ViewerStreamSource.CODEX -> CODEX
                else -> WATCHED
            }
        }
    }
}

data class RecentPostEntry(
    val post: Post,
    val viewedAtEpochMs: Long,
    val origin: ViewerStreamSource,
    val originQueryHash: String?,
    val section: RecentPostSection = RecentPostSection.fromOrigin(origin),
    val maxViewedMediaNumber: Int = 1,
)

data class RecentSearchEntry(
    val query: Query,
    val queryHash: String,
    val searchedAtEpochMs: Long,
    val kind: RecentSearchKind = query.defaultRecentSearchKind(),
    val sources: List<SourceKey> = query.defaultRecentSearchSources(),
    val sourceTags: Map<SourceKey, List<String>> = emptyMap(),
) {
    val isRetiredForYouSearch: Boolean get() = sourceTags.isNotEmpty()
}

enum class RecentSearchKind {
    SOURCE,
    UNIFIED,
    MULTI_SEARCH,
}

fun Query.defaultRecentSearchKind(): RecentSearchKind = when (mode) {
    QueryMode.Unified -> RecentSearchKind.UNIFIED
    is QueryMode.Source -> RecentSearchKind.SOURCE
}

fun Query.defaultRecentSearchSources(): List<SourceKey> = when (val queryMode = mode) {
    QueryMode.Unified -> emptyList()
    is QueryMode.Source -> listOf(queryMode.source)
}

sealed interface RecentActivityEntry {
    val occurredAtEpochMs: Long

    data class Watched(val entry: RecentPostEntry) : RecentActivityEntry {
        override val occurredAtEpochMs: Long
            get() = entry.viewedAtEpochMs
    }

    data class Search(val entry: RecentSearchEntry) : RecentActivityEntry {
        override val occurredAtEpochMs: Long
            get() = entry.searchedAtEpochMs
    }
}

interface RecentsRepository {
    fun observeWatchedPosts(): Flow<List<RecentPostEntry>>
    fun observeWatchedPostIds(): Flow<Set<PostId>> = observeWatchedPosts().map { posts ->
        posts.filter { it.section == RecentPostSection.WATCHED }.mapTo(linkedSetOf()) { it.post.id }
    }.distinctUntilChanged()
    fun observeSearches(): Flow<List<RecentSearchEntry>>
    suspend fun recordWatchedPost(
        post: Post,
        origin: ViewerStreamSource,
        originQueryHash: String?,
        section: RecentPostSection = RecentPostSection.fromOrigin(origin),
        viewedMediaNumber: Int = 1,
    )
    suspend fun recordWatchedMediaProgress(
        post: Post,
        origin: ViewerStreamSource,
        originQueryHash: String?,
        section: RecentPostSection,
        viewedMediaNumber: Int,
    )
    suspend fun recordSearch(
        query: Query,
        queryHash: String,
        kind: RecentSearchKind = query.defaultRecentSearchKind(),
        sources: List<SourceKey> = query.defaultRecentSearchSources(),
    )
    suspend fun restoreEntries(
        watchedPosts: List<RecentPostEntry> = emptyList(),
        searches: List<RecentSearchEntry> = emptyList(),
    )
    suspend fun removeWatchedPost(postId: PostId, section: RecentPostSection)
    suspend fun removeSearch(queryHash: String)
    suspend fun clearWatchedPosts(section: RecentPostSection)
    suspend fun clearSearches(): List<RecentSearchEntry>
    suspend fun clearAll()
}

data class StatisticsTagKey(
    val source: SourceKey,
    val tag: String,
)

data class SearchedTagKey(
    val source: SourceKey,
    val term: SearchTerm,
)

data class UsageDurationDelta(
    val totalMs: Long = 0L,
    val browsingMs: Long = 0L,
    val watchingMs: Long = 0L,
    val codexMs: Long = 0L,
    val readingMs: Long = 0L,
    val animeMs: Long = 0L,
    val dailyUsage: List<DailyUsageDuration> = emptyList(),
)

@Serializable
data class CodexPostStatistics(
    @SerialName("label") val label: String = "",
    @SerialName("opens") val opens: Long = 0L,
    @SerialName("videoMs") val videoMs: Long = 0L,
    @SerialName("imageMs") val imageMs: Long = 0L,
)

@Serializable
data class CodexStatistics(
    @SerialName("browsingMs") val browsingMs: Long = 0L,
    @SerialName("watchingMs") val watchingMs: Long = 0L,
    @SerialName("posts") val posts: Map<String, CodexPostStatistics> = emptyMap(),
)

data class CodexUsageTarget(val codexId: String, val postKey: String? = null, val video: Boolean = false)

data class LifetimeStatistics(
    val thumbnails: Map<String, String> = emptyMap(),
    val history: Map<java.time.LocalDate, DailyActivity> = emptyMap(),
    val codexStatistics: Map<String, CodexStatistics> = emptyMap(),
    val appOpenCount: Long = 0L,
    val totalForegroundMs: Long = 0L,
    val browsingMs: Long = 0L,
    val watchingMs: Long = 0L,
    val codexMs: Long = 0L,
    val readingMs: Long = 0L,
    val animeMs: Long = 0L,
    val watchedPostCount: Long = 0L,
    val watchedBySource: Map<SourceKey, Long> = emptyMap(),
    val watchedByTag: Map<StatisticsTagKey, Long> = emptyMap(),
    val watchedTagFloors: Map<SourceKey, Long> = emptyMap(),
    val searchCount: Long = 0L,
    val searchesBySource: Map<SourceKey, Long> = emptyMap(),
    val searchesByTag: Map<SearchedTagKey, Long> = emptyMap(),
    val searchesByTagSeeded: Boolean = false,
    val searchesByTypedTagSeeded: Boolean = false,
    val forYouSearchCount: Long = 0L,
    val forYouSaveCount: Long = 0L,
    val postUrlCopyCount: Long = 0L,
    val codexEntryCounts: Map<String, Long> = emptyMap(),
)

data class CodexPostOpen(val codexId: String, val postKey: String, val label: String)

interface StatisticsRepository {
    suspend fun recordPostAvailable(post: Post) = Unit
    fun observeStatistics(): Flow<LifetimeStatistics>
    suspend fun addCodexUsage(target: CodexUsageTarget, durationMs: Long)
    suspend fun recordCodexPostOpen(codexId: String, postKey: String, label: String)
    suspend fun recordAppOpen()
    suspend fun addUsageDuration(delta: UsageDurationDelta)
    suspend fun recordWatchedPost(source: SourceKey, tags: Set<String>, codexOpen: CodexPostOpen? = null)
    suspend fun recordSearch(sources: Set<SourceKey>, terms: Set<SearchTerm> = emptySet())
    suspend fun seedSearchedTags(recentSearches: List<RecentSearchEntry>)
    suspend fun recordForYouSave()
    suspend fun recordPostUrlCopy()
    suspend fun recordCodexEntry(codexId: String)
    suspend fun mergeRestored(imported: LifetimeStatistics)
    suspend fun flush()
}

data class LikedPost(
    val profileId: String,
    val postId: PostId,
    val likedAtEpochMs: Long,
    val tags: List<String>,
)

interface LikesRepository {
    fun observeLikes(profileId: String): Flow<List<LikedPost>>
    fun observeLikedPostIds(profileId: String): Flow<Set<PostId>>
}

data class CodexBulkImportResult(
    val codex: Codex,
    val acceptedPosts: Int,
    val insertedMemberships: Int,
)

data class CodexBulkReorderResult(
    val applied: Boolean,
    val movedCodices: Int,
)

data class CodexLikeSyncResult(
    val nowLiked: Boolean,
    val membershipChanged: Boolean,
    val automaticMembershipsAdded: Int = 0,
)

data class CodexLikesClearResult(
    val clearedLikes: Int,
    val removedMemberships: Int,
    val undo: ClearedLikesSnapshot? = null,
)

interface CodexLikesTransactions {
    suspend fun cleanCodex(request: CodexCleaningRequest): CodexCleaningSnapshot

    suspend fun restoreCleanedCodex(snapshot: CodexCleaningSnapshot)

    suspend fun removeProfileLibrary(profileId: String)

    suspend fun importCodex(
        codexId: String,
        name: String,
        posts: List<Post>,
    ): CodexBulkImportResult

    suspend fun reorderCodices(codexIdsInOrder: List<String>): CodexBulkReorderResult

    suspend fun toggleLikeAndSyncSystemCodex(
        profileId: String,
        systemCodexId: String,
        systemCodexName: String,
        post: Post,
        tags: List<String>,
        eligibleAutomaticCodexIds: Set<String> = emptySet(),
    ): CodexLikeSyncResult

    suspend fun clearLikesAndLikedMemberships(
        profileId: String,
        systemCodexId: String,
    ): CodexLikesClearResult

    suspend fun restoreClearedLikes(snapshot: ClearedLikesSnapshot)
}

private val DEFAULT_SOURCE_WEIGHTS: Map<SourceKey, Double> = mapOf(
    SourceKey.PIXIV to 0.25,
    SourceKey.GELBOORU to 0.18,
    SourceKey.RULE34XXX to 0.15,
    SourceKey.RULE34PAHEAL to 0.12,
    SourceKey.NHENTAI to 0.10,
    SourceKey.HITOMI to 0.10,
    SourceKey.IWARA to 0.08,
    SourceKey.RULE34VIDEO to 0.07,
    SourceKey.RULE34GEN to 0.07,
    SourceKey.DANBOORU to 0.12,
    SourceKey.AIBOORU to 0.03,
).let { ratios ->
    val total = ratios.values.sum()
    ratios.mapValues { (_, ratio) -> ratio / total }
}

data class SourceRuntimeSettings(
    val enabledSources: Set<SourceKey> = SourceKey.builtIns.toSet(),
    val sourceWeights: Map<SourceKey, Double> = DEFAULT_SOURCE_WEIGHTS,
    val sourceWeightsByConfiguration: Map<String, Map<SourceKey, Double>> = emptyMap(),
)

enum class ProviderHealthSnapshotStatus {
    OK,
    DEGRADED,
    FAILED,
    SKIPPED,
    UNKNOWN,
}

data class ProviderHealthSnapshot(
    val source: SourceKey,
    val status: ProviderHealthSnapshotStatus,
    val checkedAtEpochMs: Long,
    val latencyMs: Long? = null,
    val failureReason: String? = null,
    val message: String? = null,
)

data class CacheSettings(
    val cacheFullImageOnSave: Boolean = false,
    val downloadQuality: com.theoriacodex.domain.model.VideoQuality = com.theoriacodex.domain.model.VideoQuality.BEST,
    val downloadsOverMetered: Boolean = true,
    val downloadsOverRoaming: Boolean = false,
)

data class ContentFilterSettings(
    val resolveUnknownAnimatedDurations: Boolean = false,
)

data class ViewerSettings(
    val playbackMode: com.theoriacodex.domain.model.ViewerPlaybackMode = com.theoriacodex.domain.model.ViewerPlaybackMode.LOOP,
    val videoQuality: com.theoriacodex.domain.model.VideoQuality = com.theoriacodex.domain.model.VideoQuality.AUTO,
    val invertMultiImageScrollDirection: Boolean = false,
    val instantSeek: Boolean = false,
    val orientationLock: com.theoriacodex.domain.model.ViewerOrientation? = null,
    val commentOrder: com.theoriacodex.domain.model.CommentOrder = com.theoriacodex.domain.model.CommentOrder.OLDEST,
)

data class RecommendationProfile(
    val profileId: String,
    val name: String,
)

data class ForYouBlacklistEntry(
    val source: SourceKey,
    val tags: List<String>,
)

data class FavoriteTagEntry(
    val source: SourceKey,
    val tags: List<String>,
    val name: String? = null,
) {
    constructor(source: SourceKey, tag: String) : this(source, listOf(tag))
}

private val DEFAULT_RECOMMENDATION_PROFILES = listOf(
    RecommendationProfile(profileId = "profile-main", name = "Main"),
    RecommendationProfile(profileId = "profile-alt", name = "Alt"),
)

fun defaultRecommendationProfiles(): List<RecommendationProfile> {
    return DEFAULT_RECOMMENDATION_PROFILES
}

enum class CodexLibrarySortKey { MANUAL, NAME, DATE_ADDED, POSTS, WATCH_TIME, OPENS }

enum class CodexLibrarySort(key: CodexLibrarySortKey, direction: SortDirection?) : DirectedSort<CodexLibrarySortKey> {
    MANUAL(CodexLibrarySortKey.MANUAL, null),
    NAME_ASCENDING(CodexLibrarySortKey.NAME, SortDirection.ASCENDING),
    NAME_DESCENDING(CodexLibrarySortKey.NAME, SortDirection.DESCENDING),
    NEWEST(CodexLibrarySortKey.DATE_ADDED, SortDirection.DESCENDING),
    OLDEST(CodexLibrarySortKey.DATE_ADDED, SortDirection.ASCENDING),
    MOST_ITEMS(CodexLibrarySortKey.POSTS, SortDirection.DESCENDING),
    FEWEST_ITEMS(CodexLibrarySortKey.POSTS, SortDirection.ASCENDING),
    MOST_WATCHED(CodexLibrarySortKey.WATCH_TIME, SortDirection.DESCENDING),
    LEAST_WATCHED(CodexLibrarySortKey.WATCH_TIME, SortDirection.ASCENDING),
    MOST_OPENED(CodexLibrarySortKey.OPENS, SortDirection.DESCENDING),
    LEAST_OPENED(CodexLibrarySortKey.OPENS, SortDirection.ASCENDING),
    ;

    override val choice = SortChoice(key, direction)
}

enum class SeriesUpdateInterval(val hours: Int?) {
    OFF(null),
    HOURS_6(6),
    HOURS_12(12),
    HOURS_24(24),
}

data class AppSettings(
    val oldSearchBar: Boolean = false,
    val seriesUpdateInterval: SeriesUpdateInterval = SeriesUpdateInterval.HOURS_12,
    val seriesDeleteAfterReading: Boolean = false,
    val codexLibrarySort: CodexLibrarySort = CodexLibrarySort.MANUAL,
    val followedCreators: List<FollowedCreator> = emptyList(),
    val runtime: SourceRuntimeSettings = SourceRuntimeSettings(),
    val cache: CacheSettings = CacheSettings(),
    val contentFilters: ContentFilterSettings = ContentFilterSettings(),
    val viewer: ViewerSettings = ViewerSettings(),
    val recommendationProfiles: List<RecommendationProfile> = defaultRecommendationProfiles(),
    val activeProfileId: String = defaultRecommendationProfiles().first().profileId,
    val forYouBlacklistByProfile: Map<String, List<ForYouBlacklistEntry>> = emptyMap(),
    val favoriteTagsByProfile: Map<String, List<FavoriteTagEntry>> = emptyMap(),
    val providerHealth: Map<SourceKey, ProviderHealthSnapshot> = emptyMap(),
)

interface SettingsRepository {
    fun observeSettings(): Flow<AppSettings>
    suspend fun updateSettings(transform: (AppSettings) -> AppSettings)
    suspend fun setEnabledSources(enabledSources: Set<SourceKey>)
    suspend fun setCacheFullImageOnSave(enabled: Boolean)
    suspend fun setResolveUnknownAnimatedDurations(enabled: Boolean)
    suspend fun setInvertMultiImageScrollDirection(enabled: Boolean)
    suspend fun setActiveProfile(profileId: String)
    suspend fun addRecommendationProfile(name: String): RecommendationProfile
    suspend fun renameRecommendationProfile(profileId: String, name: String): Boolean
    suspend fun removeRecommendationProfile(profileId: String): Boolean
    suspend fun addForYouBlacklistEntry(profileId: String, source: SourceKey, tags: List<String>): Boolean
    suspend fun removeForYouBlacklistEntry(profileId: String, source: SourceKey, tags: List<String>): Boolean
    suspend fun addFavoriteTag(profileId: String, source: SourceKey, tags: List<String>, name: String? = null): Boolean
    suspend fun removeFavoriteTag(profileId: String, source: SourceKey, tags: List<String>): Boolean
    suspend fun renameFavoriteTag(profileId: String, source: SourceKey, tags: List<String>, name: String?): Boolean
}

data class CacheSnapshot(
    val thumbnailCount: Int,
    val fullImageCount: Int,
    val thumbnailBytes: Long = 0L,
    val fullImageBytes: Long = 0L,
)

interface CacheRepository {
    fun observeNamedThumbnailRevisions(): Flow<Long> = flowOf(0L)
    fun observeSnapshot(): Flow<CacheSnapshot>
    suspend fun cacheThumbnail(post: Post)
    suspend fun cacheNamedThumbnail(name: String, post: Post) {
        cacheThumbnail(post)
    }
    suspend fun cacheFull(post: Post)
    suspend fun clearThumbnailCache()
    suspend fun clearFullImageCache()
}

enum class ViewerStreamSource {
    SEARCH,
    FOR_YOU,
    RELATED,
    CODEX,
    CREATOR_PROFILE,
    RECENTS,
    TRAIL,

    SERIES,
}

data class ViewerLaunchContext(
    val queryHash: String,
    val startIndex: Int,
    val streamSource: ViewerStreamSource,
    val scrollOffsetHint: Int,
    val recentsSection: RecentPostSection? = null,
)

internal fun decodeRestoredRecentsSection(
    encoded: String?,
    streamSource: ViewerStreamSource,
    queryHash: String,
): RecentPostSection? {
    val decoded = encoded?.let { value ->
        runCatching { RecentPostSection.valueOf(value) }.getOrNull()
    }
    if (decoded != null || streamSource != ViewerStreamSource.RECENTS) return decoded
    return when (queryHash) {
        "recents:codex" -> RecentPostSection.CODEX
        "recents:watched" -> RecentPostSection.WATCHED
        else -> null
    }
}

data class SearchScrollState(
    val firstVisibleItemIndex: Int,
    val firstVisibleItemOffsetPx: Int,
)

@Serializable
data class FeedFabRestoreState(
    @SerialName("excludedSavedSearches") val excludedSavedSearches: List<String> = emptyList(),
    @SerialName("followedSources") val followedSources: List<String> = emptyList(),
    @SerialName("followedAuthors") val followedAuthors: List<String> = emptyList(),
    @SerialName("animatedOnly") val animatedOnly: Boolean = false,
    @SerialName("hideLiked") val hideLiked: Boolean = false,
    @SerialName("hideSaved") val hideSaved: Boolean = false,
    @SerialName("hideWatched") val hideWatched: Boolean = false,
    @SerialName("durationMinBucket") val durationMinBucket: Int = 0,
    @SerialName("durationMaxBucket") val durationMaxBucket: Int = 25,
    @SerialName("sortMode") val sortMode: String? = null,
    @SerialName("source") val source: String? = null,
    @SerialName("language") val language: String? = null,
    @SerialName("fullColorOnly") val fullColorOnly: Boolean = false,
    @SerialName("grid") val grid: Boolean = false,
    @SerialName("searchAnimationByScope")
    val searchAnimationByScope: Map<String, SearchAnimationFilterRestoreState> = emptyMap(),
)

@Serializable
data class SearchAnimationFilterRestoreState(
    @SerialName("durationMinBucket") val durationMinBucket: Int = 0,
    @SerialName("durationMaxBucket") val durationMaxBucket: Int = 25,
)

interface UiRestoreRepository {
    suspend fun setLastTab(route: String)
    suspend fun getLastTab(): String?

    suspend fun setLibrarySection(section: String)
    suspend fun getLibrarySection(): String?

    fun observeSavedSearchNewPosts(): Flow<SavedSearchNewPosts>
    suspend fun updateSavedSearchNewPosts(transform: (SavedSearchNewPosts) -> SavedSearchNewPosts)
    suspend fun setSearchScrollState(queryHash: String, state: SearchScrollState)
    suspend fun getSearchScrollState(queryHash: String): SearchScrollState?
    suspend fun setFeedFabRestoreState(contextKey: String, state: FeedFabRestoreState)
    suspend fun getFeedFabRestoreStates(): Map<String, FeedFabRestoreState>
    fun observeViewerLaunchContext(): Flow<ViewerLaunchContext?>
    suspend fun setViewerLaunchContext(context: ViewerLaunchContext?)
}

fun interface SourceLibraryPresence {
    suspend fun hasLibraryData(source: SourceKey): Boolean
}
