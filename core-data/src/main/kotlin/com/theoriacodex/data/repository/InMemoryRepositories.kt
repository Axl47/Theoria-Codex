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

private const val DEFAULT_RECENT_WATCHED_LIMIT = 200
private const val DEFAULT_RECENT_SEARCH_LIMIT = 100

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
            val resolvedName = RepositoryPolicies.resolveUniqueCodexName(
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
            val resolvedName = RepositoryPolicies.resolveUniqueCodexName(
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
            codices.value = RepositoryPolicies.reorderCodices(
                codices = codices.value,
                codexId = codexId,
                targetIndex = targetIndex,
            )
        }
    }

    override suspend fun renameCodex(codexId: String, name: String) {
        mutex.withLock {
            val current = codices.value
            val existing = current.firstOrNull { it.codexId == codexId } ?: return@withLock
            val resolvedName = RepositoryPolicies.resolveUniqueCodexName(
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
            RepositoryPolicies.sortCodexPairs(pairs, sort).map { it.second }
        }
    }

    override suspend fun getPost(postId: PostId): Post? {
        return postsById.value[postId]
    }

    override suspend fun addItem(codexId: String, post: Post) {
        mutex.withLock {
            if (codices.value.none { it.codexId == codexId }) return@withLock
            val existing = itemsByCodex.value[codexId].orEmpty()
            val updated = RepositoryPolicies.addCodexItem(
                items = existing,
                codexId = codexId,
                postId = post.id,
                savedAtEpochMs = System.currentTimeMillis(),
            )
            val postChanged = postsById.value[post.id] != post
            if (postChanged) {
                postsById.value = postsById.value + (post.id to post)
            }
            if (updated != existing) {
                itemsByCodex.value = itemsByCodex.value + (codexId to updated)
            }
        }
    }

    override suspend fun updatePost(post: Post) {
        mutex.withLock {
            if (post.id !in postsById.value) return@withLock
            postsById.value = postsById.value + (post.id to post)
        }
    }

    override suspend fun removeItem(codexId: String, sourceKey: SourceKey, sourcePostId: String) {
        mutex.withLock {
            val targetPostId = PostId(source = sourceKey, sourcePostId = sourcePostId)
            val updated = RepositoryPolicies.removeCodexItem(
                items = itemsByCodex.value[codexId].orEmpty(),
                postId = targetPostId,
            )
            itemsByCodex.value = itemsByCodex.value + (codexId to updated)
        }
    }
}

class InMemoryQueryRepository : QueryRepository {
    private val mutex = Mutex()
    private val queryState = MutableStateFlow<Map<String, Query>>(emptyMap())

    override fun observeAppliedQuery(modeKey: String): Flow<Query?> {
        return queryState.map { it[modeKey] }
    }

    override suspend fun upsertAppliedQuery(modeKey: String, query: Query) {
        mutex.withLock {
            queryState.value = queryState.value + (modeKey to query)
        }
    }

}

class InMemoryRecentsRepository(
    private val watchedLimit: Int = DEFAULT_RECENT_WATCHED_LIMIT,
    private val searchLimit: Int = DEFAULT_RECENT_SEARCH_LIMIT,
    private val clock: () -> Long = System::currentTimeMillis,
) : RecentsRepository {
    private val mutex = Mutex()
    private val watched = MutableStateFlow<List<RecentPostEntry>>(emptyList())
    private val searches = MutableStateFlow<List<RecentSearchEntry>>(emptyList())

    override fun observeWatchedPosts(): Flow<List<RecentPostEntry>> {
        return watched
    }

    override fun observeSearches(): Flow<List<RecentSearchEntry>> {
        return searches
    }

    override fun observeActivity(): Flow<List<RecentActivityEntry>> {
        return combine(watched, searches) { watchedPosts, searchEntries ->
            RepositoryPolicies.mergeRecentActivity(watchedPosts, searchEntries)
        }
    }

    override suspend fun recordWatchedPost(
        post: Post,
        origin: ViewerStreamSource,
        originQueryHash: String?,
        section: RecentPostSection,
    ) {
        mutex.withLock {
            val previousEntry = watched.value.firstOrNull { entry ->
                entry.post.id == post.id && entry.section == section
            }
            val effectiveOrigin = previousEntry?.origin.takeIf { origin == ViewerStreamSource.RECENTS } ?: origin
            val effectiveQueryHash = previousEntry?.originQueryHash.takeIf { origin == ViewerStreamSource.RECENTS }
                ?: originQueryHash
            watched.value = RepositoryPolicies.recordWatched(
                entries = watched.value,
                entry = RecentPostEntry(
                    post = post,
                    viewedAtEpochMs = clock(),
                    origin = effectiveOrigin,
                    originQueryHash = effectiveQueryHash,
                    section = section,
                ),
                limit = watchedLimit,
            )
        }
    }

    override suspend fun recordSearch(query: Query, queryHash: String) {
        val normalizedHash = queryHash.trim()
        if (normalizedHash.isBlank()) return
        mutex.withLock {
            searches.value = RepositoryPolicies.recordSearch(
                entries = searches.value,
                entry = RecentSearchEntry(
                    query = query,
                    queryHash = normalizedHash,
                    searchedAtEpochMs = clock(),
                ),
                limit = searchLimit,
            )
        }
    }

    override suspend fun restoreEntries(
        watchedPosts: List<RecentPostEntry>,
        searches: List<RecentSearchEntry>,
    ) {
        mutex.withLock {
            watched.value = RepositoryPolicies.normalizeRecentWatched(
                entries = watched.value + watchedPosts,
                limit = watchedLimit,
            )
            this.searches.value = RepositoryPolicies.normalizeRecentSearches(
                entries = this.searches.value + searches,
                limit = searchLimit,
            )
        }
    }

    override suspend fun clearWatchedPosts() {
        mutex.withLock {
            watched.value = emptyList()
        }
    }

    override suspend fun clearWatchedPosts(section: RecentPostSection) {
        mutex.withLock {
            watched.value = watched.value.filterNot { entry -> entry.section == section }
        }
    }

    override suspend fun clearSearches() {
        mutex.withLock {
            searches.value = emptyList()
        }
    }

    override suspend fun clearAll() {
        mutex.withLock {
            watched.value = emptyList()
            searches.value = emptyList()
        }
    }
}

class InMemorySettingsRepository : SettingsRepository {
    private val mutex = Mutex()
    private val settings = MutableStateFlow(RepositoryPolicies.normalizeSettings(AppSettings()))

    override fun observeSettings(): Flow<AppSettings> {
        return settings
    }

    override suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        mutateSettings { current ->
            RepositoryPolicies.Result(state = transform(current), value = Unit)
        }
    }

    override suspend fun setEnabledSources(enabledSources: Set<SourceKey>) {
        updateSettings { current ->
            current.copy(
                runtime = current.runtime.copy(enabledSources = enabledSources),
            )
        }
    }

    override suspend fun setSourceWeights(sourceWeights: Map<SourceKey, Double>) {
        updateSettings { current ->
            current.copy(
                runtime = current.runtime.copy(sourceWeights = sourceWeights),
            )
        }
    }

    override suspend fun setCacheFullImageOnSave(enabled: Boolean) {
        updateSettings { current ->
            current.copy(cache = current.cache.copy(cacheFullImageOnSave = enabled))
        }
    }

    override suspend fun setResolveUnknownAnimatedDurations(enabled: Boolean) {
        updateSettings { current ->
            current.copy(
                contentFilters = current.contentFilters.copy(resolveUnknownAnimatedDurations = enabled),
            )
        }
    }

    override suspend fun setInvertMultiImageScrollDirection(enabled: Boolean) {
        updateSettings { current ->
            current.copy(viewer = current.viewer.copy(invertMultiImageScrollDirection = enabled))
        }
    }

    override suspend fun setScenarioPreset(preset: ScenarioPreset) {
        updateSettings { current -> current.copy(scenarioPreset = preset) }
    }

    @Deprecated("Last-tab state is owned by UiRestoreRepository; retain this writer only for compatibility.")
    override suspend fun setLastTab(route: String) {
        updateSettings { current -> current.copy(lastSelectedTabRoute = route) }
    }

    override suspend fun setActiveProfile(profileId: String) {
        updateSettings { current -> current.copy(activeProfileId = profileId) }
    }

    override suspend fun addRecommendationProfile(name: String): RecommendationProfile {
        val profileId = UUID.randomUUID().toString()
        return mutateSettings { current ->
            RepositoryPolicies.addRecommendationProfile(current, name, profileId)
        }
    }

    override suspend fun removeRecommendationProfile(profileId: String): Boolean {
        return mutateSettings { current ->
            RepositoryPolicies.removeRecommendationProfile(current, profileId)
        }
    }

    override suspend fun addForYouBlacklistEntry(profileId: String, source: SourceKey, tags: List<String>): Boolean {
        return mutateSettings { current ->
            RepositoryPolicies.addBlacklistEntry(current, profileId, source, tags)
        }
    }

    override suspend fun removeForYouBlacklistEntry(profileId: String, source: SourceKey, tags: List<String>): Boolean {
        return mutateSettings { current ->
            RepositoryPolicies.removeBlacklistEntry(current, profileId, source, tags)
        }
    }

    override suspend fun addFavoriteTag(profileId: String, source: SourceKey, tag: String): Boolean {
        return mutateSettings { current ->
            RepositoryPolicies.addFavoriteTag(current, profileId, source, tag)
        }
    }

    override suspend fun removeFavoriteTag(profileId: String, source: SourceKey, tag: String): Boolean {
        return mutateSettings { current ->
            RepositoryPolicies.removeFavoriteTag(current, profileId, source, tag)
        }
    }

    override suspend fun setProviderHealthSnapshots(snapshots: List<ProviderHealthSnapshot>) {
        updateSettings { current -> RepositoryPolicies.mergeProviderHealth(current, snapshots) }
    }

    private suspend fun <T> mutateSettings(
        policy: (AppSettings) -> RepositoryPolicies.Result<AppSettings, T>,
    ): T {
        return mutex.withLock {
            val result = policy(settings.value)
            settings.value = RepositoryPolicies.normalizeSettings(result.state)
            result.value
        }
    }
}

class InMemoryLikesRepository(
    private val clock: () -> Long = System::currentTimeMillis,
) : LikesRepository {
    private val mutex = Mutex()
    private val likesByProfile = MutableStateFlow<Map<String, Map<PostId, LikedPost>>>(emptyMap())

    override fun observeLikes(profileId: String): Flow<List<LikedPost>> {
        return likesByProfile.map { byProfile ->
            byProfile[RepositoryPolicies.normalizeProfileId(profileId)]
                .orEmpty()
                .values
                .sortedByDescending { it.likedAtEpochMs }
        }
    }

    override fun observeLikedPostIds(profileId: String): Flow<Set<PostId>> {
        return likesByProfile.map { byProfile ->
            byProfile[RepositoryPolicies.normalizeProfileId(profileId)].orEmpty().keys
        }
    }

    override suspend fun toggleLike(profileId: String, postId: PostId, tags: List<String>): Boolean {
        return mutex.withLock {
            val result = RepositoryPolicies.toggleLike(
                likesByProfile = likesByProfile.value,
                profileId = profileId,
                postId = postId,
                tags = tags,
                likedAtEpochMs = clock(),
            )
            likesByProfile.value = result.state
            result.value
        }
    }

    override suspend fun clearLikes(profileId: String) {
        mutex.withLock {
            likesByProfile.value = likesByProfile.value - RepositoryPolicies.normalizeProfileId(profileId)
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
    private val settingsSectionExpansion = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    private val viewerLaunchContext = MutableStateFlow<ViewerLaunchContext?>(null)

    override suspend fun setLastTab(route: String) {
        mutex.withLock {
            lastTab.value = route
        }
    }

    override suspend fun getLastTab(): String? {
        return lastTab.value
    }

    override suspend fun migrateLegacyLastTab(legacyRoute: String?): String? {
        return mutex.withLock {
            lastTab.value?.let { current -> return@withLock current }
            val migrated = legacyRoute?.trim()?.takeIf { route -> route.isNotEmpty() }
                ?: return@withLock null
            lastTab.value = migrated
            migrated
        }
    }

    override suspend fun setSearchScrollState(queryHash: String, state: SearchScrollState) {
        mutex.withLock {
            scrollStates.value = scrollStates.value + (queryHash to state)
        }
    }

    override suspend fun getSearchScrollState(queryHash: String): SearchScrollState? {
        return scrollStates.value[queryHash]
    }

    override suspend fun setSettingsSectionExpansion(expansion: Map<String, Boolean>) {
        mutex.withLock {
            settingsSectionExpansion.value = expansion.toMap()
        }
    }

    override suspend fun getSettingsSectionExpansion(): Map<String, Boolean> {
        return settingsSectionExpansion.value
    }

    override fun observeViewerLaunchContext(): Flow<ViewerLaunchContext?> {
        return viewerLaunchContext
    }

    override suspend fun setViewerLaunchContext(context: ViewerLaunchContext?) {
        viewerLaunchContext.value = context
    }
}
