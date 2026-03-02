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

    override suspend fun ensureCodex(codexId: String, name: String): Codex {
        return mutex.withLock {
            val current = codices.value
            val existing = current.firstOrNull { it.codexId == codexId }
            val resolvedName = resolveUniqueCodexName(
                requestedName = name,
                existingCodices = current,
                excludeCodexId = codexId,
            )
            if (existing != null) {
                if (existing.name == resolvedName) {
                    existing
                } else {
                    val updated = existing.copy(name = resolvedName)
                    codices.value = codices.value.map { codex ->
                        if (codex.codexId == codexId) updated else codex
                    }
                    updated
                }
            } else {
                val created = Codex(
                    codexId = codexId,
                    name = resolvedName,
                    createdAtEpochMs = System.currentTimeMillis(),
                )
                codices.value = codices.value + created
                created
            }
        }
    }

    override suspend fun createCodex(name: String): Codex {
        return mutex.withLock {
            val resolvedName = resolveUniqueCodexName(
                requestedName = name,
                existingCodices = codices.value,
            )
            val created = Codex(
                codexId = UUID.randomUUID().toString(),
                name = resolvedName,
                createdAtEpochMs = System.currentTimeMillis(),
            )
            codices.value = codices.value + created
            created
        }
    }

    override suspend fun reorderCodex(codexId: String, targetIndex: Int) {
        mutex.withLock {
            val current = codices.value
            if (current.isEmpty()) return@withLock
            val sourceIndex = current.indexOfFirst { codex -> codex.codexId == codexId }
            if (sourceIndex < 0) return@withLock

            val clampedTarget = targetIndex.coerceIn(0, current.lastIndex)
            if (sourceIndex == clampedTarget) return@withLock

            val reordered = current.toMutableList()
            val moved = reordered.removeAt(sourceIndex)
            reordered.add(clampedTarget, moved)
            codices.value = reordered
        }
    }

    override suspend fun renameCodex(codexId: String, name: String) {
        mutex.withLock {
            val current = codices.value
            val existing = current.firstOrNull { it.codexId == codexId } ?: return@withLock
            val resolvedName = resolveUniqueCodexName(
                requestedName = name,
                existingCodices = current,
                excludeCodexId = codexId,
            )
            if (existing.name == resolvedName) return@withLock
            codices.value = codices.value.map {
                if (it.codexId == codexId) it.copy(name = resolvedName) else it
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

    override suspend fun setActiveProfile(profileId: String) {
        settings.value = normalizeSettings(settings.value.copy(activeProfileId = profileId))
    }

    override suspend fun addRecommendationProfile(name: String): RecommendationProfile {
        val profileName = name.trim().ifBlank {
            "Profile ${settings.value.recommendationProfiles.size + 1}"
        }
        val created = RecommendationProfile(
            profileId = UUID.randomUUID().toString(),
            name = profileName,
        )
        settings.value = normalizeSettings(
            settings.value.copy(
                recommendationProfiles = settings.value.recommendationProfiles + created,
                activeProfileId = created.profileId,
            )
        )
        return created
    }

    override suspend fun removeRecommendationProfile(profileId: String): Boolean {
        val current = settings.value
        if (current.recommendationProfiles.size <= 1) return false
        if (current.recommendationProfiles.none { it.profileId == profileId }) return false

        val remaining = current.recommendationProfiles.filterNot { it.profileId == profileId }
        val nextActive = if (current.activeProfileId == profileId) {
            remaining.first().profileId
        } else {
            current.activeProfileId
        }
        settings.value = normalizeSettings(
            current.copy(
                recommendationProfiles = remaining,
                activeProfileId = nextActive,
            )
        )
        return true
    }
}

class InMemoryLikesRepository : LikesRepository {
    private val mutex = Mutex()
    private val likesByProfile = MutableStateFlow<Map<String, Map<PostId, LikedPost>>>(emptyMap())

    override fun observeLikes(profileId: String): Flow<List<LikedPost>> {
        return likesByProfile.map { byProfile ->
            byProfile[profileId]
                .orEmpty()
                .values
                .sortedByDescending { it.likedAtEpochMs }
        }
    }

    override fun observeLikedPostIds(profileId: String): Flow<Set<PostId>> {
        return likesByProfile.map { byProfile ->
            byProfile[profileId].orEmpty().keys
        }
    }

    override suspend fun toggleLike(profileId: String, postId: PostId, tags: List<String>): Boolean {
        return mutex.withLock {
            val profileLikes = likesByProfile.value[profileId].orEmpty().toMutableMap()
            val existing = profileLikes[postId]
            if (existing != null) {
                profileLikes -= postId
                likesByProfile.value = likesByProfile.value + (profileId to profileLikes)
                false
            } else {
                profileLikes[postId] = LikedPost(
                    profileId = profileId,
                    postId = postId,
                    likedAtEpochMs = System.currentTimeMillis(),
                    tags = normalizeLikedTags(tags),
                )
                likesByProfile.value = likesByProfile.value + (profileId to profileLikes)
                true
            }
        }
    }

    override suspend fun clearLikes(profileId: String) {
        mutex.withLock {
            likesByProfile.value = likesByProfile.value - profileId
        }
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

private fun normalizeLikedTags(tags: List<String>): List<String> {
    return tags
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinctBy { it.lowercase() }
        .toList()
}

private fun resolveUniqueCodexName(
    requestedName: String,
    existingCodices: List<Codex>,
    excludeCodexId: String? = null,
): String {
    val baseName = requestedName.trim().ifBlank { "Codex" }
    val occupiedNames = existingCodices
        .asSequence()
        .filter { codex -> codex.codexId != excludeCodexId }
        .map { codex -> normalizeCodexNameKey(codex.name) }
        .toSet()
    if (normalizeCodexNameKey(baseName) !in occupiedNames) {
        return baseName
    }

    var suffix = 2
    while (true) {
        val candidate = "$baseName $suffix"
        if (normalizeCodexNameKey(candidate) !in occupiedNames) {
            return candidate
        }
        suffix += 1
    }
}

private fun normalizeCodexNameKey(name: String): String {
    return name.trim().lowercase()
}

private fun normalizeSettings(settings: AppSettings): AppSettings {
    val normalizedWeights = normalizeWeights(
        enabledSources = settings.runtime.enabledSources,
        rawWeights = settings.runtime.sourceWeights,
    )
    val normalizedProfiles = settings.recommendationProfiles
        .asSequence()
        .map { profile ->
            RecommendationProfile(
                profileId = profile.profileId.trim(),
                name = profile.name.trim(),
            )
        }
        .filter { profile -> profile.profileId.isNotBlank() && profile.name.isNotBlank() }
        .distinctBy { profile -> profile.profileId }
        .toList()
        .ifEmpty { defaultRecommendationProfiles() }
    val activeProfileId = settings.activeProfileId
        .takeIf { active -> normalizedProfiles.any { profile -> profile.profileId == active } }
        ?: normalizedProfiles.first().profileId
    return settings.copy(
        runtime = settings.runtime.copy(sourceWeights = normalizedWeights),
        recommendationProfiles = normalizedProfiles,
        activeProfileId = activeProfileId,
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
