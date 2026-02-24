package com.theoriacodex.data.repository

import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.Codex
import com.theoriacodex.domain.model.CodexItem
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.flow.Flow

interface CodexRepository {
    fun observeCodices(): Flow<List<Codex>>
    suspend fun createCodex(name: String): Codex
    suspend fun renameCodex(codexId: String, name: String)
    suspend fun deleteCodex(codexId: String)
    fun observeCodexItems(codexId: String): Flow<List<CodexItem>>
    suspend fun addItem(codexId: String, post: Post)
    suspend fun removeItem(codexId: String, sourceKey: SourceKey, sourcePostId: String)
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

data class AppSettings(
    val runtime: SourceRuntimeSettings = SourceRuntimeSettings(),
    val cache: CacheSettings = CacheSettings(),
    val lastSelectedTabRoute: String = "search",
)

interface SettingsRepository {
    fun observeSettings(): Flow<AppSettings>
    suspend fun updateSettings(transform: (AppSettings) -> AppSettings)
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
