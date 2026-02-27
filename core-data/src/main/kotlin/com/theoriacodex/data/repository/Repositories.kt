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
    val profile: UserProfile,
    val postId: PostId,
    val likedAtEpochMs: Long,
    val tags: List<String>,
)

interface LikesRepository {
    fun observeLikes(profile: UserProfile): Flow<List<LikedPost>>
    fun observeLikedPostIds(profile: UserProfile): Flow<Set<PostId>>
    suspend fun toggleLike(profile: UserProfile, postId: PostId, tags: List<String>): Boolean
    suspend fun clearLikes(profile: UserProfile)
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

enum class UserProfile {
    USER_1,
    USER_2,
}

data class AppSettings(
    val runtime: SourceRuntimeSettings = SourceRuntimeSettings(),
    val cache: CacheSettings = CacheSettings(),
    val scenarioPreset: ScenarioPreset = ScenarioPreset.NORMAL,
    val lastSelectedTabRoute: String = "search",
    val activeProfile: UserProfile = UserProfile.USER_1,
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
    suspend fun setActiveProfile(profile: UserProfile)
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
