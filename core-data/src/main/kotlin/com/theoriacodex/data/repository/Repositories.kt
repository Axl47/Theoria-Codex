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
        SourceKey.PIXIV to 0.5,
        SourceKey.GELBOORU to 0.3,
        SourceKey.AIBOORU to 0.2,
    ),
)

data class CacheSettings(
    val cacheFullImageOnSave: Boolean = false,
)

data class RecommendationProfile(
    val profileId: String,
    val name: String,
)

data class ForYouBlacklistEntry(
    val source: SourceKey,
    val tags: List<String>,
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
    val scenarioPreset: ScenarioPreset = ScenarioPreset.NORMAL,
    val lastSelectedTabRoute: String = "search",
    val recommendationProfiles: List<RecommendationProfile> = defaultRecommendationProfiles(),
    val activeProfileId: String = defaultRecommendationProfiles().first().profileId,
    val forYouBlacklistByProfile: Map<String, List<ForYouBlacklistEntry>> = emptyMap(),
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
    suspend fun setScenarioPreset(preset: ScenarioPreset)
    suspend fun setLastTab(route: String)
    suspend fun setActiveProfile(profileId: String)
    suspend fun addRecommendationProfile(name: String): RecommendationProfile
    suspend fun removeRecommendationProfile(profileId: String): Boolean
    suspend fun addForYouBlacklistEntry(profileId: String, source: SourceKey, tags: List<String>): Boolean
    suspend fun removeForYouBlacklistEntry(profileId: String, source: SourceKey, tags: List<String>): Boolean
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
