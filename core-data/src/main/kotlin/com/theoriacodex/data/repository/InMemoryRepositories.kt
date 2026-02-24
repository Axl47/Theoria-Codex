package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.Codex
import com.theoriacodex.domain.model.CodexItem
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SourceKey
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class InMemoryCodexRepository : CodexRepository {
    private val mutex = Mutex()
    private val codices = MutableStateFlow<List<Codex>>(emptyList())
    private val itemsByCodex = MutableStateFlow<Map<String, List<CodexItem>>>(emptyMap())
    private val postsById = MutableStateFlow<Map<PostId, Post>>(emptyMap())

    override fun observeCodices(): Flow<List<Codex>> {
        return codices
    }

    override fun observeCodex(codexId: String): Flow<Codex?> {
        return codices.map { all -> all.firstOrNull { it.codexId == codexId } }
    }

    override suspend fun createCodex(name: String): Codex {
        return mutex.withLock {
            val created = Codex(
                codexId = UUID.randomUUID().toString(),
                name = name,
                createdAtEpochMs = System.currentTimeMillis(),
            )
            codices.value = codices.value + created
            created
        }
    }

    override suspend fun renameCodex(codexId: String, name: String) {
        mutex.withLock {
            codices.value = codices.value.map {
                if (it.codexId == codexId) it.copy(name = name) else it
            }
        }
    }

    override suspend fun deleteCodex(codexId: String) {
        mutex.withLock {
            codices.value = codices.value.filterNot { it.codexId == codexId }
            itemsByCodex.value = itemsByCodex.value - codexId
        }
    }

    override fun observeCodexItems(codexId: String): Flow<List<CodexItem>> {
        return itemsByCodex.map { map -> map[codexId].orEmpty() }
    }

    override fun observeCodexPosts(codexId: String, sort: CodexSortMode): Flow<List<Post>> {
        return combine(itemsByCodex, postsById) { items, posts ->
            val pairs = items[codexId].orEmpty().mapNotNull { item ->
                posts[item.postId]?.let { post -> item to post }
            }
            sortCodexPairs(pairs, sort).map { it.second }
        }
    }

    override suspend fun getPost(postId: PostId): Post? {
        return postsById.value[postId]
    }

    override suspend fun addItem(codexId: String, post: Post) {
        mutex.withLock {
            if (codices.value.none { it.codexId == codexId }) return@withLock
            postsById.value = postsById.value + (post.id to post)
            val existing = itemsByCodex.value[codexId].orEmpty()
            val contains = existing.any { it.postId == post.id }
            if (!contains) {
                val updated = existing + CodexItem(
                    codexId = codexId,
                    postId = post.id,
                    savedAtEpochMs = System.currentTimeMillis(),
                )
                itemsByCodex.value = itemsByCodex.value + (codexId to updated)
            }
        }
    }

    override suspend fun removeItem(codexId: String, sourceKey: SourceKey, sourcePostId: String) {
        mutex.withLock {
            val targetPostId = PostId(source = sourceKey, sourcePostId = sourcePostId)
            val updated = itemsByCodex.value[codexId].orEmpty().filterNot { it.postId == targetPostId }
            itemsByCodex.value = itemsByCodex.value + (codexId to updated)
        }
    }
}

class InMemoryQueryRepository : QueryRepository {
    private val mutex = Mutex()
    private val queryState = MutableStateFlow<Map<String, Query>>(emptyMap())
    private val scrollOffsets = MutableStateFlow<Map<String, Int>>(emptyMap())

    override fun observeAppliedQuery(modeKey: String): Flow<Query?> {
        return queryState.map { it[modeKey] }
    }

    override suspend fun upsertAppliedQuery(modeKey: String, query: Query) {
        mutex.withLock {
            queryState.value = queryState.value + (modeKey to query)
        }
    }

    override suspend fun upsertScrollOffset(queryHash: String, offsetPx: Int) {
        mutex.withLock {
            scrollOffsets.value = scrollOffsets.value + (queryHash to offsetPx)
        }
    }

    override suspend fun getScrollOffset(queryHash: String): Int? {
        return scrollOffsets.value[queryHash]
    }
}

class InMemorySettingsRepository : SettingsRepository {
    private val settings = MutableStateFlow(AppSettings())

    override fun observeSettings(): Flow<AppSettings> {
        return settings
    }

    override suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        settings.value = normalizeSettings(transform(settings.value))
    }

    override suspend fun setEnabledSources(enabledSources: Set<SourceKey>) {
        settings.value = normalizeSettings(
            settings.value.copy(
                runtime = settings.value.runtime.copy(enabledSources = enabledSources),
            )
        )
    }

    override suspend fun setSourceWeights(sourceWeights: Map<SourceKey, Double>) {
        settings.value = normalizeSettings(
            settings.value.copy(
                runtime = settings.value.runtime.copy(sourceWeights = sourceWeights),
            )
        )
    }

    override suspend fun setCacheFullImageOnSave(enabled: Boolean) {
        settings.value = settings.value.copy(cache = settings.value.cache.copy(cacheFullImageOnSave = enabled))
    }

    override suspend fun setScenarioPreset(preset: ScenarioPreset) {
        settings.value = settings.value.copy(scenarioPreset = preset)
    }

    override suspend fun setLastTab(route: String) {
        settings.value = settings.value.copy(lastSelectedTabRoute = route)
    }
}

class InMemoryCacheRepository : CacheRepository {
    private val mutex = Mutex()
    private val thumbnailCache = mutableSetOf<PostId>()
    private val fullCache = mutableSetOf<PostId>()
    private val snapshot = MutableStateFlow(CacheSnapshot(thumbnailCount = 0, fullImageCount = 0))

    override fun observeSnapshot(): Flow<CacheSnapshot> {
        return snapshot
    }

    override suspend fun cacheThumbnail(post: Post) {
        mutex.withLock {
            thumbnailCache += post.id
            emitSnapshot()
        }
    }

    override suspend fun cacheFull(post: Post) {
        mutex.withLock {
            fullCache += post.id
            emitSnapshot()
        }
    }

    override suspend fun clearThumbnailCache() {
        mutex.withLock {
            thumbnailCache.clear()
            emitSnapshot()
        }
    }

    override suspend fun clearFullImageCache() {
        mutex.withLock {
            fullCache.clear()
            emitSnapshot()
        }
    }

    private fun emitSnapshot() {
        snapshot.value = CacheSnapshot(
            thumbnailCount = thumbnailCache.size,
            fullImageCount = fullCache.size,
        )
    }
}

class InMemoryUiRestoreRepository : UiRestoreRepository {
    private val mutex = Mutex()
    private val lastTab = MutableStateFlow<String?>(null)
    private val scrollStates = MutableStateFlow<Map<String, SearchScrollState>>(emptyMap())
    private val viewerLaunchContext = MutableStateFlow<ViewerLaunchContext?>(null)

    override suspend fun setLastTab(route: String) {
        lastTab.value = route
    }

    override suspend fun getLastTab(): String? {
        return lastTab.value
    }

    override suspend fun setSearchScrollState(queryHash: String, state: SearchScrollState) {
        mutex.withLock {
            scrollStates.value = scrollStates.value + (queryHash to state)
        }
    }

    override suspend fun getSearchScrollState(queryHash: String): SearchScrollState? {
        return scrollStates.value[queryHash]
    }

    override fun observeViewerLaunchContext(): Flow<ViewerLaunchContext?> {
        return viewerLaunchContext
    }

    override suspend fun setViewerLaunchContext(context: ViewerLaunchContext?) {
        viewerLaunchContext.value = context
    }
}

private fun normalizeSettings(settings: AppSettings): AppSettings {
    val normalizedWeights = normalizeWeights(
        enabledSources = settings.runtime.enabledSources,
        rawWeights = settings.runtime.sourceWeights,
    )
    return settings.copy(
        runtime = settings.runtime.copy(sourceWeights = normalizedWeights),
    )
}

private fun normalizeWeights(
    enabledSources: Set<SourceKey>,
    rawWeights: Map<SourceKey, Double>,
): Map<SourceKey, Double> {
    if (enabledSources.isEmpty()) return emptyMap()
    val defaults = SourceRuntimeSettings().sourceWeights
    val positiveWeights = enabledSources.associateWith { source ->
        val raw = rawWeights[source] ?: defaults[source] ?: 1.0
        raw.coerceAtLeast(0.0)
    }
    val total = positiveWeights.values.sum().takeIf { it > 0.0 } ?: enabledSources.size.toDouble()
    return positiveWeights.mapValues { (_, weight) -> weight / total }
}

private fun sortCodexPairs(
    pairs: List<Pair<CodexItem, Post>>,
    sort: CodexSortMode,
): List<Pair<CodexItem, Post>> {
    return when (sort) {
        CodexSortMode.NEWEST_SAVED -> pairs.sortedByDescending { it.first.savedAtEpochMs }
        CodexSortMode.OLDEST_SAVED -> pairs.sortedBy { it.first.savedAtEpochMs }
        CodexSortMode.BY_SOURCE -> pairs.sortedWith(
            compareBy<Pair<CodexItem, Post>> { it.second.id.source.name }
                .thenByDescending { it.first.savedAtEpochMs }
                .thenBy { it.second.id.sourcePostId }
        )
    }
}
