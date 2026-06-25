package com.theoriacodex.data.repository

import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.Codex
import com.theoriacodex.domain.model.CodexItem
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
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
    suspend fun removeItem(codexId: String, sourceKey: SourceKey, sourcePostId: String)
}

enum class CodexSortMode {
    NEWEST_SAVED,
    OLDEST_SAVED,
    BY_SOURCE,
}

interface QueryRepository {
    fun observeAppliedQuery(modeKey: String): Flow<Query?>
    suspend fun upsertAppliedQuery(modeKey: String, query: Query)
    suspend fun upsertScrollOffset(queryHash: String, offsetPx: Int)
    suspend fun getScrollOffset(queryHash: String): Int?
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

data class SourceRuntimeSettings(
    val enabledSources: Set<SourceKey> = SourceKey.entries.toSet(),
    val sourceWeights: Map<SourceKey, Double> = mapOf(
        SourceKey.PIXIV to 0.25,
        SourceKey.GELBOORU to 0.18,
        SourceKey.RULE34XXX to 0.15,
        SourceKey.RULE34PAHEAL to 0.12,
        SourceKey.NHENTAI to 0.10,
        SourceKey.IWARA to 0.08,
        SourceKey.RULE34VIDEO to 0.07,
        SourceKey.RULE34GEN to 0.07,
        SourceKey.AIBOORU to 0.03,
    ),
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
    val scenarioPreset: ScenarioPreset = ScenarioPreset.NORMAL,
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
    suspend fun setScenarioPreset(preset: ScenarioPreset)
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
}

data class ViewerLaunchContext(
    val queryHash: String,
    val startIndex: Int,
    val streamSource: ViewerStreamSource,
    val scrollOffsetHint: Int,
)

data class SearchScrollState(
    val firstVisibleItemIndex: Int,
    val firstVisibleItemOffsetPx: Int,
)

interface UiRestoreRepository {
    suspend fun setLastTab(route: String)
    suspend fun getLastTab(): String?
    suspend fun setSearchScrollState(queryHash: String, state: SearchScrollState)
    suspend fun getSearchScrollState(queryHash: String): SearchScrollState?
    fun observeViewerLaunchContext(): Flow<ViewerLaunchContext?>
    suspend fun setViewerLaunchContext(context: ViewerLaunchContext?)
}
