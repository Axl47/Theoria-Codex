package com.theoriacodex.data.repository

import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.Codex
import com.theoriacodex.domain.model.CodexItem
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.flow.Flow

interface CodexRepository {
    fun observeCodices(): Flow<List<Codex>>
    fun observeCodex(codexId: String): Flow<Codex?>
    suspend fun ensureCodex(codexId: String, name: String): Codex
    suspend fun createCodex(name: String): Codex
    suspend fun reorderCodex(codexId: String, targetIndex: Int)
    suspend fun renameCodex(codexId: String, name: String)
    suspend fun deleteCodex(codexId: String)
    fun observeCodexItems(codexId: String): Flow<List<CodexItem>>
    fun observeCodexPosts(codexId: String, sort: CodexSortMode): Flow<List<Post>>
    suspend fun getPost(postId: com.theoriacodex.domain.model.PostId): Post?
    suspend fun addItem(codexId: String, post: Post)
    suspend fun updatePost(post: Post)
    suspend fun removeItem(codexId: String, sourceKey: SourceKey, sourcePostId: String)
    suspend fun removeItems(codexId: String, postIds: Set<PostId>)
    suspend fun restoreItems(items: List<CodexItem>, posts: List<Post>)
}

enum class CodexSortMode {
    NEWEST_SAVED,
    OLDEST_SAVED,
    BY_SOURCE,
}

interface QueryRepository {
    fun observeAppliedQuery(modeKey: String): Flow<Query?>
    suspend fun upsertAppliedQuery(modeKey: String, query: Query)
}

enum class RecentPostSection {
    WATCHED,
    CODEX,
    FYP,
    ;

    companion object {
        fun fromOrigin(origin: ViewerStreamSource): RecentPostSection {
            return when (origin) {
                ViewerStreamSource.CODEX -> CODEX
                ViewerStreamSource.FOR_YOU -> FYP
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
)

data class RecentSearchEntry(
    val query: Query,
    val queryHash: String,
    val searchedAtEpochMs: Long,
    val kind: RecentSearchKind = query.defaultRecentSearchKind(),
    val sources: List<SourceKey> = query.defaultRecentSearchSources(),
)

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
    fun observeSearches(): Flow<List<RecentSearchEntry>>
    fun observeActivity(): Flow<List<RecentActivityEntry>>
    suspend fun recordWatchedPost(
        post: Post,
        origin: ViewerStreamSource,
        originQueryHash: String?,
        section: RecentPostSection = RecentPostSection.fromOrigin(origin),
    )
    suspend fun recordRecentPosts(
        posts: List<Post>,
        origin: ViewerStreamSource,
        originQueryHash: String?,
        section: RecentPostSection = RecentPostSection.fromOrigin(origin),
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
    suspend fun clearWatchedPosts()
    suspend fun clearWatchedPosts(section: RecentPostSection)
    suspend fun clearSearches()
    suspend fun clearAll()
}

interface ExploreRepository {
    suspend fun trendingTags(limit: Int): List<TagSuggestion>
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
    suspend fun toggleLike(profileId: String, postId: PostId, tags: List<String>): Boolean
    suspend fun clearLikes(profileId: String)
}

data class CodexBulkImportResult(
    val codex: Codex,
    /** Valid unique posts accepted by the transaction, including existing membership. */
    val acceptedPosts: Int,
    /** Membership rows newly inserted by this transaction. */
    val insertedMemberships: Int,
)

data class CodexBulkReorderResult(
    /** False when the requested ids were not one complete, duplicate-free permutation. */
    val applied: Boolean,
    val movedCodices: Int,
)

data class CodexLikeSyncResult(
    val nowLiked: Boolean,
    /** Whether system-Codex membership changed along with the like. */
    val membershipChanged: Boolean,
)

data class CodexLikesClearResult(
    val clearedLikes: Int,
    /** Only memberships matching the profile's liked post ids are removed. */
    val removedMemberships: Int,
)

data class CodexProfileDeleteResult(
    val clearedLikes: Int,
    val systemCodexDeleted: Boolean,
)

/**
 * Optional repository capability for operations that cross Codex and Likes ownership.
 *
 * Implementations must commit each method atomically. Ordinary [CodexRepository] and
 * [LikesRepository] remain the narrow read/write contracts for callers touching one aggregate.
 * Callers can opt into this capability when both repositories are backed by one transactional
 * store, without making Room or another database part of the domain-facing API.
 */
interface CodexLikesTransactions {
    suspend fun importCodex(
        codexId: String,
        name: String,
        posts: List<Post>,
    ): CodexBulkImportResult

    /** Applies one complete order in one commit; partial or duplicate orders are rejected. */
    suspend fun reorderCodices(codexIdsInOrder: List<String>): CodexBulkReorderResult

    suspend fun toggleLikeAndSyncSystemCodex(
        profileId: String,
        systemCodexId: String,
        systemCodexName: String,
        post: Post,
        tags: List<String>,
    ): CodexLikeSyncResult

    /**
     * Clears a profile's likes and removes only the corresponding system-Codex memberships.
     * Any independently saved membership in that Codex is retained.
     */
    suspend fun clearLikesAndLikedMemberships(
        profileId: String,
        systemCodexId: String,
    ): CodexLikesClearResult

    /** Profile deletion owns both its likes and its entire profile-specific system Codex. */
    suspend fun clearLikesAndDeleteSystemCodex(
        profileId: String,
        systemCodexId: String,
    ): CodexProfileDeleteResult

    /** Destructive reset used only by an explicit whole-content recovery flow. */
    suspend fun clearAllContent()
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
    SourceKey.AIBOORU to 0.03,
).let { ratios ->
    val total = ratios.values.sum()
    ratios.mapValues { (_, ratio) -> ratio / total }
}

data class SourceRuntimeSettings(
    val enabledSources: Set<SourceKey> = SourceKey.entries.toSet(),
    val sourceWeights: Map<SourceKey, Double> = DEFAULT_SOURCE_WEIGHTS,
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
)

data class ContentFilterSettings(
    val resolveUnknownAnimatedDurations: Boolean = true,
)

data class ViewerSettings(
    val invertMultiImageScrollDirection: Boolean = false,
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
    val tag: String,
)

private val DEFAULT_RECOMMENDATION_PROFILES = listOf(
    RecommendationProfile(profileId = "profile-main", name = "Main"),
    RecommendationProfile(profileId = "profile-alt", name = "Alt"),
)

fun defaultRecommendationProfiles(): List<RecommendationProfile> {
    return DEFAULT_RECOMMENDATION_PROFILES
}

data class AppSettings(
    val runtime: SourceRuntimeSettings = SourceRuntimeSettings(),
    val cache: CacheSettings = CacheSettings(),
    val contentFilters: ContentFilterSettings = ContentFilterSettings(),
    val viewer: ViewerSettings = ViewerSettings(),
    val scenarioPreset: ScenarioPreset = ScenarioPreset.NORMAL,
    /** Legacy migration input. New last-tab state is owned by [UiRestoreRepository]. */
    val lastSelectedTabRoute: String = "search",
    val recommendationProfiles: List<RecommendationProfile> = defaultRecommendationProfiles(),
    val activeProfileId: String = defaultRecommendationProfiles().first().profileId,
    val forYouBlacklistByProfile: Map<String, List<ForYouBlacklistEntry>> = emptyMap(),
    val favoriteTagsByProfile: Map<String, List<FavoriteTagEntry>> = emptyMap(),
    val providerHealth: Map<SourceKey, ProviderHealthSnapshot> = emptyMap(),
)

enum class ScenarioPreset {
    NORMAL,
    PARTIAL_FAILURE,
    EMPTY_RESULTS,
    SLOW_NETWORK,
}

interface SettingsRepository {
    fun observeSettings(): Flow<AppSettings>
    suspend fun updateSettings(transform: (AppSettings) -> AppSettings)
    suspend fun setEnabledSources(enabledSources: Set<SourceKey>)
    suspend fun setSourceWeights(sourceWeights: Map<SourceKey, Double>)
    suspend fun setCacheFullImageOnSave(enabled: Boolean)
    suspend fun setResolveUnknownAnimatedDurations(enabled: Boolean)
    suspend fun setInvertMultiImageScrollDirection(enabled: Boolean)
    suspend fun setScenarioPreset(preset: ScenarioPreset)
    @Deprecated("Last-tab state is owned by UiRestoreRepository; retain this writer only for compatibility.")
    suspend fun setLastTab(route: String)
    suspend fun setActiveProfile(profileId: String)
    suspend fun addRecommendationProfile(name: String): RecommendationProfile
    suspend fun removeRecommendationProfile(profileId: String): Boolean
    suspend fun addForYouBlacklistEntry(profileId: String, source: SourceKey, tags: List<String>): Boolean
    suspend fun removeForYouBlacklistEntry(profileId: String, source: SourceKey, tags: List<String>): Boolean
    suspend fun addFavoriteTag(profileId: String, source: SourceKey, tag: String): Boolean
    suspend fun removeFavoriteTag(profileId: String, source: SourceKey, tag: String): Boolean
    suspend fun setProviderHealthSnapshots(snapshots: List<ProviderHealthSnapshot>)
}

data class CacheSnapshot(
    val thumbnailCount: Int,
    val fullImageCount: Int,
)

interface CacheRepository {
    fun observeSnapshot(): Flow<CacheSnapshot>
    suspend fun cacheThumbnail(post: Post)
    suspend fun cacheFull(post: Post)
    suspend fun clearThumbnailCache()
    suspend fun clearFullImageCache()
}

enum class ViewerStreamSource {
    SEARCH,
    FOR_YOU,
    CODEX,
    CREATOR_PROFILE,
    RECENTS,
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
        "recents:fyp" -> RecentPostSection.FYP
        "recents:watched" -> RecentPostSection.WATCHED
        else -> null
    }
}

data class SearchScrollState(
    val firstVisibleItemIndex: Int,
    val firstVisibleItemOffsetPx: Int,
)

interface UiRestoreRepository {
    suspend fun setLastTab(route: String)
    suspend fun getLastTab(): String?
    suspend fun migrateLegacyLastTab(legacyRoute: String?): String?
    suspend fun setSearchScrollState(queryHash: String, state: SearchScrollState)
    suspend fun getSearchScrollState(queryHash: String): SearchScrollState?
    suspend fun setSettingsSectionExpansion(expansion: Map<String, Boolean>)
    suspend fun getSettingsSectionExpansion(): Map<String, Boolean>
    fun observeViewerLaunchContext(): Flow<ViewerLaunchContext?>
    suspend fun setViewerLaunchContext(context: ViewerLaunchContext?)
}
