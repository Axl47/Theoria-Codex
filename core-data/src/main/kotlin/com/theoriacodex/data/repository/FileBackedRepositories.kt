package com.theoriacodex.data.repository

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.theoriacodex.domain.model.Codex
import com.theoriacodex.domain.model.CodexItem
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.DateRange
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.PostTaxonomyTerm
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.tags.normalizeFavoriteTagForStorage
import com.theoriacodex.domain.tags.sourceTagKey
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val json: Gson = GsonBuilder().setPrettyPrinting().create()
private const val DEFAULT_RECENT_WATCHED_LIMIT = 200
private const val DEFAULT_RECENT_SEARCH_LIMIT = 100

class FileBackedCodexRepository(
    baseDirectory: File,
) : CodexRepository {
    private val mutex = Mutex()
    private val storageFile = baseDirectory.resolve("codex_store.json")
    private val codicesFlow = MutableStateFlow<List<Codex>>(emptyList())
    private val itemsFlow = MutableStateFlow<Map<String, List<CodexItem>>>(emptyMap())
    private val postsFlow = MutableStateFlow<Map<PostId, Post>>(emptyMap())

    init {
        storageFile.parentFile?.mkdirs()
        val stored = readJson(storageFile, CodexStoreFile())
        codicesFlow.value = stored.codices.map { it.toDomain() }
        itemsFlow.value = stored.items.mapValues { entry -> entry.value.mapNotNull { it.toDomainOrNull() } }
        postsFlow.value = stored.posts.mapNotNull { record -> record.toDomainOrNull() }.associate { post ->
            post.id to post
        }
    }

    override fun observeCodices(): Flow<List<Codex>> {
        return codicesFlow
    }

    override fun observeCodex(codexId: String): Flow<Codex?> {
        return codicesFlow.map { codices -> codices.firstOrNull { it.codexId == codexId } }
    }

    override suspend fun ensureCodex(codexId: String, name: String): Codex {
        return mutex.withLock {
            val current = codicesFlow.value
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
                    codicesFlow.value = codicesFlow.value.map { codex ->
                        if (codex.codexId == codexId) updated else codex
                    }
                    persist()
                    updated
                }
            } else {
                val codex = Codex(
                    codexId = codexId,
                    name = resolvedName,
                    createdAtEpochMs = System.currentTimeMillis(),
                )
                codicesFlow.value = codicesFlow.value + codex
                persist()
                codex
            }
        }
    }

    override suspend fun createCodex(name: String): Codex {
        return mutex.withLock {
            val resolvedName = resolveUniqueCodexName(
                requestedName = name,
                existingCodices = codicesFlow.value,
            )
            val codex = Codex(
                codexId = UUID.randomUUID().toString(),
                name = resolvedName,
                createdAtEpochMs = System.currentTimeMillis(),
            )
            codicesFlow.value = codicesFlow.value + codex
            persist()
            codex
        }
    }

    override suspend fun reorderCodex(codexId: String, targetIndex: Int) {
        mutex.withLock {
            val current = codicesFlow.value
            if (current.isEmpty()) return@withLock
            val sourceIndex = current.indexOfFirst { codex -> codex.codexId == codexId }
            if (sourceIndex < 0) return@withLock

            val clampedTarget = targetIndex.coerceIn(0, current.lastIndex)
            if (sourceIndex == clampedTarget) return@withLock

            val reordered = current.toMutableList()
            val moved = reordered.removeAt(sourceIndex)
            reordered.add(clampedTarget, moved)
            codicesFlow.value = reordered
            persist()
        }
    }

    override suspend fun renameCodex(codexId: String, name: String) {
        mutex.withLock {
            val current = codicesFlow.value
            val existing = current.firstOrNull { it.codexId == codexId } ?: return@withLock
            val resolvedName = resolveUniqueCodexName(
                requestedName = name,
                existingCodices = current,
                excludeCodexId = codexId,
            )
            if (existing.name == resolvedName) return@withLock
            codicesFlow.value = codicesFlow.value.map { codex ->
                if (codex.codexId == codexId) codex.copy(name = resolvedName) else codex
            }
            persist()
        }
    }

    override suspend fun deleteCodex(codexId: String) {
        mutex.withLock {
            codicesFlow.value = codicesFlow.value.filterNot { it.codexId == codexId }
            itemsFlow.value = itemsFlow.value - codexId
            persist()
        }
    }

    override fun observeCodexItems(codexId: String): Flow<List<CodexItem>> {
        return itemsFlow.map { map -> map[codexId].orEmpty() }
    }

    override fun observeCodexPosts(codexId: String, sort: CodexSortMode): Flow<List<Post>> {
        return combine(itemsFlow, postsFlow) { itemsByCodex, postsById ->
            val pairs = itemsByCodex[codexId].orEmpty().mapNotNull { item ->
                postsById[item.postId]?.let { post -> item to post }
            }
            sortCodexPairs(pairs, sort).map { it.second }
        }
    }

    override suspend fun getPost(postId: PostId): Post? {
        return postsFlow.value[postId]
    }

    override suspend fun addItem(codexId: String, post: Post) {
        mutex.withLock {
            if (codicesFlow.value.none { it.codexId == codexId }) return@withLock
            postsFlow.value = postsFlow.value + (post.id to post)
            val existing = itemsFlow.value[codexId].orEmpty()
            val alreadyExists = existing.any { it.postId == post.id }
            if (!alreadyExists) {
                val updated = existing + CodexItem(
                    codexId = codexId,
                    postId = post.id,
                    savedAtEpochMs = System.currentTimeMillis(),
                )
                itemsFlow.value = itemsFlow.value + (codexId to updated)
                persist()
            }
        }
    }

    override suspend fun updatePost(post: Post) {
        mutex.withLock {
            val existing = postsFlow.value[post.id] ?: return@withLock
            if (existing == post) return@withLock
            postsFlow.value = postsFlow.value + (post.id to post)
            persist()
        }
    }

    override suspend fun removeItem(codexId: String, sourceKey: SourceKey, sourcePostId: String) {
        mutex.withLock {
            val target = PostId(source = sourceKey, sourcePostId = sourcePostId)
            val updated = itemsFlow.value[codexId].orEmpty().filterNot { it.postId == target }
            itemsFlow.value = itemsFlow.value + (codexId to updated)
            persist()
        }
    }

    private fun persist() {
        val toPersist = CodexStoreFile(
            codices = codicesFlow.value.map { CodexRecord.fromDomain(it) },
            items = itemsFlow.value.mapValues { entry -> entry.value.map { CodexItemRecord.fromDomain(it) } },
            posts = postsFlow.value.values.map { PostRecord.fromDomain(it) },
        )
        writeJson(storageFile, toPersist)
    }
}

class FileBackedQueryRepository(
    baseDirectory: File,
) : QueryRepository {
    private val mutex = Mutex()
    private val storageFile = baseDirectory.resolve("query_store.json")
    private val queriesFlow = MutableStateFlow<Map<String, Query>>(emptyMap())
    private val offsetsFlow = MutableStateFlow<Map<String, Int>>(emptyMap())

    init {
        storageFile.parentFile?.mkdirs()
        val stored = readJson(storageFile, QueryStoreFile())
        queriesFlow.value = stored.queries.mapValues { (_, record) -> record.toDomain() }
        offsetsFlow.value = stored.scrollOffsets
    }

    override fun observeAppliedQuery(modeKey: String): Flow<Query?> {
        return queriesFlow.map { it[modeKey] }
    }

    override suspend fun upsertAppliedQuery(modeKey: String, query: Query) {
        mutex.withLock {
            queriesFlow.value = queriesFlow.value + (modeKey to query)
            persist()
        }
    }

    override suspend fun upsertScrollOffset(queryHash: String, offsetPx: Int) {
        mutex.withLock {
            offsetsFlow.value = offsetsFlow.value + (queryHash to offsetPx)
            persist()
        }
    }

    override suspend fun getScrollOffset(queryHash: String): Int? {
        return offsetsFlow.value[queryHash]
    }

    private fun persist() {
        val payload = QueryStoreFile(
            queries = queriesFlow.value.mapValues { (_, query) -> QueryRecord.fromDomain(query) },
            scrollOffsets = offsetsFlow.value,
        )
        writeJson(storageFile, payload)
    }
}

class FileBackedRecentsRepository(
    baseDirectory: File,
    private val watchedLimit: Int = DEFAULT_RECENT_WATCHED_LIMIT,
    private val searchLimit: Int = DEFAULT_RECENT_SEARCH_LIMIT,
    private val clock: () -> Long = System::currentTimeMillis,
) : RecentsRepository {
    private val mutex = Mutex()
    private val storageFile = baseDirectory.resolve("recents_store.json")
    private val watchedFlow = MutableStateFlow<List<RecentPostEntry>>(emptyList())
    private val searchesFlow = MutableStateFlow<List<RecentSearchEntry>>(emptyList())

    init {
        storageFile.parentFile?.mkdirs()
        val stored = readJson(storageFile, RecentsStoreFile())
        watchedFlow.value = stored.watchedPosts
            .orEmpty()
            .mapNotNull { record -> record.toDomainOrNull() }
            .dedupeRecentWatched()
            .take(watchedLimit.coerceAtLeast(0))
        searchesFlow.value = stored.searches
            .orEmpty()
            .mapNotNull { record -> record.toDomainOrNull() }
            .dedupeRecentSearches()
            .take(searchLimit.coerceAtLeast(0))
    }

    override fun observeWatchedPosts(): Flow<List<RecentPostEntry>> {
        return watchedFlow
    }

    override fun observeSearches(): Flow<List<RecentSearchEntry>> {
        return searchesFlow
    }

    override fun observeActivity(): Flow<List<RecentActivityEntry>> {
        return combine(watchedFlow, searchesFlow) { watchedPosts, searchEntries ->
            buildList {
                watchedPosts.forEach { entry -> add(RecentActivityEntry.Watched(entry)) }
                searchEntries.forEach { entry -> add(RecentActivityEntry.Search(entry)) }
            }.sortedByDescending { entry -> entry.occurredAtEpochMs }
        }
    }

    override suspend fun recordWatchedPost(post: Post, origin: ViewerStreamSource, originQueryHash: String?) {
        mutex.withLock {
            watchedFlow.value = (listOf(
                RecentPostEntry(
                    post = post,
                    viewedAtEpochMs = clock(),
                    origin = origin,
                    originQueryHash = originQueryHash,
                )
            ) + watchedFlow.value.filterNot { entry -> entry.post.id == post.id })
                .take(watchedLimit.coerceAtLeast(0))
            persist()
        }
    }

    override suspend fun recordSearch(query: Query, queryHash: String) {
        val normalizedHash = queryHash.trim()
        if (normalizedHash.isBlank()) return
        mutex.withLock {
            searchesFlow.value = (listOf(
                RecentSearchEntry(
                    query = query,
                    queryHash = normalizedHash,
                    searchedAtEpochMs = clock(),
                )
            ) + searchesFlow.value.filterNot { entry -> entry.queryHash == normalizedHash })
                .take(searchLimit.coerceAtLeast(0))
            persist()
        }
    }

    override suspend fun clearWatchedPosts() {
        mutex.withLock {
            watchedFlow.value = emptyList()
            persist()
        }
    }

    override suspend fun clearSearches() {
        mutex.withLock {
            searchesFlow.value = emptyList()
            persist()
        }
    }

    override suspend fun clearAll() {
        mutex.withLock {
            watchedFlow.value = emptyList()
            searchesFlow.value = emptyList()
            persist()
        }
    }

    private fun persist() {
        writeJson(
            storageFile,
            RecentsStoreFile(
                watchedPosts = watchedFlow.value.map(RecentPostRecord::fromDomain),
                searches = searchesFlow.value.map(RecentSearchRecord::fromDomain),
            ),
        )
    }
}

class FileBackedSettingsRepository(
    baseDirectory: File,
) : SettingsRepository {
    private val mutex = Mutex()
    private val storageFile = baseDirectory.resolve("settings_store.json")
    private val settingsFlow = MutableStateFlow(AppSettings())

    init {
        storageFile.parentFile?.mkdirs()
        val stored = readJson(storageFile, SettingsStoreFile.fromDomain(AppSettings()))
        settingsFlow.value = stored.toDomain()
    }

    override fun observeSettings(): Flow<AppSettings> {
        return settingsFlow
    }

    override suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        mutex.withLock {
            settingsFlow.value = normalizeSettings(transform(settingsFlow.value))
            persist()
        }
    }

    override suspend fun setEnabledSources(enabledSources: Set<SourceKey>) {
        updateSettings {
            it.copy(
                runtime = it.runtime.copy(enabledSources = enabledSources),
            )
        }
    }

    override suspend fun setSourceWeights(sourceWeights: Map<SourceKey, Double>) {
        updateSettings {
            it.copy(
                runtime = it.runtime.copy(sourceWeights = sourceWeights),
            )
        }
    }

    override suspend fun setCacheFullImageOnSave(enabled: Boolean) {
        updateSettings {
            it.copy(
                cache = it.cache.copy(cacheFullImageOnSave = enabled),
            )
        }
    }

    override suspend fun setResolveUnknownAnimatedDurations(enabled: Boolean) {
        updateSettings {
            it.copy(
                contentFilters = it.contentFilters.copy(resolveUnknownAnimatedDurations = enabled),
            )
        }
    }

    override suspend fun setInvertMultiImageScrollDirection(enabled: Boolean) {
        updateSettings {
            it.copy(
                viewer = it.viewer.copy(invertMultiImageScrollDirection = enabled),
            )
        }
    }

    override suspend fun setScenarioPreset(preset: ScenarioPreset) {
        updateSettings {
            it.copy(scenarioPreset = preset)
        }
    }

    override suspend fun setLastTab(route: String) {
        updateSettings {
            it.copy(lastSelectedTabRoute = route)
        }
    }

    override suspend fun setActiveProfile(profileId: String) {
        updateSettings {
            it.copy(activeProfileId = profileId)
        }
    }

    override suspend fun addRecommendationProfile(name: String): RecommendationProfile {
        val profileName = name.trim().ifBlank {
            "Profile ${settingsFlow.value.recommendationProfiles.size + 1}"
        }
        val created = RecommendationProfile(
            profileId = UUID.randomUUID().toString(),
            name = profileName,
        )
        updateSettings {
            it.copy(
                recommendationProfiles = it.recommendationProfiles + created,
                activeProfileId = created.profileId,
            )
        }
        return created
    }

    override suspend fun removeRecommendationProfile(profileId: String): Boolean {
        if (settingsFlow.value.recommendationProfiles.size <= 1) return false
        if (settingsFlow.value.recommendationProfiles.none { it.profileId == profileId }) return false

        updateSettings { current ->
            val remaining = current.recommendationProfiles.filterNot { it.profileId == profileId }
            val nextActiveId = if (current.activeProfileId == profileId) {
                remaining.first().profileId
            } else {
                current.activeProfileId
            }
            current.copy(
                recommendationProfiles = remaining,
                activeProfileId = nextActiveId,
                forYouBlacklistByProfile = current.forYouBlacklistByProfile - profileId,
                favoriteTagsByProfile = current.favoriteTagsByProfile - profileId,
            )
        }
        return true
    }

    override suspend fun addForYouBlacklistEntry(profileId: String, source: SourceKey, tags: List<String>): Boolean {
        val normalizedTags = normalizeBlacklistTags(tags)
        if (normalizedTags.isEmpty()) return false
        val current = settingsFlow.value
        val existing = current.forYouBlacklistByProfile[profileId].orEmpty()
        val alreadyPresent = existing.any { entry ->
            entry.source == source && normalizeBlacklistTags(entry.tags) == normalizedTags
        }
        if (alreadyPresent) return false

        val updated = existing + ForYouBlacklistEntry(
            source = source,
            tags = normalizedTags,
        )
        updateSettings {
            it.copy(
                forYouBlacklistByProfile = it.forYouBlacklistByProfile + (profileId to updated),
            )
        }
        return true
    }

    override suspend fun removeForYouBlacklistEntry(profileId: String, source: SourceKey, tags: List<String>): Boolean {
        val normalizedTags = normalizeBlacklistTags(tags)
        if (normalizedTags.isEmpty()) return false
        val current = settingsFlow.value
        val existing = current.forYouBlacklistByProfile[profileId].orEmpty()
        if (existing.isEmpty()) return false
        val updated = existing.filterNot { entry ->
            entry.source == source && normalizeBlacklistTags(entry.tags) == normalizedTags
        }
        if (updated.size == existing.size) return false

        val updatedMap = current.forYouBlacklistByProfile.toMutableMap()
        if (updated.isEmpty()) {
            updatedMap.remove(profileId)
        } else {
            updatedMap[profileId] = updated
        }
        updateSettings {
            it.copy(forYouBlacklistByProfile = updatedMap)
        }
        return true
    }

    override suspend fun addFavoriteTag(profileId: String, source: SourceKey, tag: String): Boolean {
        val normalizedTag = normalizeFavoriteTagForStorage(source, tag)
        if (normalizedTag.isBlank()) return false
        val current = settingsFlow.value
        val existing = current.favoriteTagsByProfile[profileId].orEmpty()
        val alreadyPresent = existing.any { entry ->
            entry.source == source && sourceTagKey(source, entry.tag) == sourceTagKey(source, normalizedTag)
        }
        if (alreadyPresent) return false

        val updated = existing + FavoriteTagEntry(
            source = source,
            tag = normalizedTag,
        )
        updateSettings {
            it.copy(
                favoriteTagsByProfile = it.favoriteTagsByProfile + (profileId to updated),
            )
        }
        return true
    }

    override suspend fun removeFavoriteTag(profileId: String, source: SourceKey, tag: String): Boolean {
        val normalizedKey = sourceTagKey(source, tag)
        if (normalizedKey.isBlank()) return false
        val current = settingsFlow.value
        val existing = current.favoriteTagsByProfile[profileId].orEmpty()
        if (existing.isEmpty()) return false
        val updated = existing.filterNot { entry ->
            entry.source == source && sourceTagKey(source, entry.tag) == normalizedKey
        }
        if (updated.size == existing.size) return false

        val updatedMap = current.favoriteTagsByProfile.toMutableMap()
        if (updated.isEmpty()) {
            updatedMap.remove(profileId)
        } else {
            updatedMap[profileId] = updated
        }
        updateSettings {
            it.copy(favoriteTagsByProfile = updatedMap)
        }
        return true
    }

    override suspend fun setProviderHealthSnapshots(snapshots: List<ProviderHealthSnapshot>) {
        updateSettings { current ->
            val merged = current.providerHealth.toMutableMap()
            snapshots.forEach { snapshot ->
                merged[snapshot.source] = snapshot
            }
            current.copy(providerHealth = merged)
        }
    }

    private fun persist() {
        writeJson(storageFile, SettingsStoreFile.fromDomain(settingsFlow.value))
    }
}

class FileBackedCacheRepository(
    baseDirectory: File,
) : CacheRepository {
    private val mutex = Mutex()
    private val thumbnailDir = baseDirectory.resolve("cache/thumbnails")
    private val fullDir = baseDirectory.resolve("cache/full")
    private val snapshotFlow = MutableStateFlow(CacheSnapshot(thumbnailCount = 0, fullImageCount = 0))

    init {
        thumbnailDir.mkdirs()
        fullDir.mkdirs()
        snapshotFlow.value = currentSnapshot()
    }

    override fun observeSnapshot(): Flow<CacheSnapshot> {
        return snapshotFlow
    }

    override suspend fun cacheThumbnail(post: Post) {
        mutex.withLock {
            writeCachedEntry(
                targetDirectory = thumbnailDir,
                key = cacheKey(post.id),
                localPath = post.preview.localPath,
                fallbackUrl = post.preview.url,
            )
            snapshotFlow.value = currentSnapshot()
        }
    }

    override suspend fun cacheFull(post: Post) {
        mutex.withLock {
            val fullImage = post.full ?: return@withLock
            writeCachedEntry(
                targetDirectory = fullDir,
                key = cacheKey(post.id),
                localPath = fullImage.localPath,
                fallbackUrl = fullImage.url,
            )
            snapshotFlow.value = currentSnapshot()
        }
    }

    override suspend fun clearThumbnailCache() {
        mutex.withLock {
            thumbnailDir.deleteRecursively()
            thumbnailDir.mkdirs()
            snapshotFlow.value = currentSnapshot()
        }
    }

    override suspend fun clearFullImageCache() {
        mutex.withLock {
            fullDir.deleteRecursively()
            fullDir.mkdirs()
            snapshotFlow.value = currentSnapshot()
        }
    }

    private fun writeCachedEntry(
        targetDirectory: File,
        key: String,
        localPath: String?,
        fallbackUrl: String?,
    ) {
        if (localPath != null) {
            val localFile = File(localPath)
            if (localFile.exists()) {
                val extension = localFile.extension.takeIf { it.isNotBlank() } ?: "bin"
                val output = targetDirectory.resolve("$key.$extension")
                Files.copy(localFile.toPath(), output.toPath(), REPLACE_EXISTING)
                return
            }
        }

        val output = targetDirectory.resolve("$key.url")
        output.writeText(fallbackUrl.orEmpty())
    }

    private fun currentSnapshot(): CacheSnapshot {
        return CacheSnapshot(
            thumbnailCount = thumbnailDir.listFiles()?.count { it.isFile } ?: 0,
            fullImageCount = fullDir.listFiles()?.count { it.isFile } ?: 0,
        )
    }

    private fun cacheKey(postId: PostId): String {
        return "${postId.source.name}_${postId.sourcePostId}"
    }
}

class FileBackedUiRestoreRepository(
    baseDirectory: File,
) : UiRestoreRepository {
    private val mutex = Mutex()
    private val storageFile = baseDirectory.resolve("ui_restore_store.json")
    private val viewerContextFlow = MutableStateFlow<ViewerLaunchContext?>(null)
    private val scrollStates = mutableMapOf<String, SearchScrollState>()
    private var lastTab: String? = null

    init {
        storageFile.parentFile?.mkdirs()
        val stored = readJson(storageFile, UiRestoreStoreFile())
        lastTab = stored.lastTab
        scrollStates.putAll(
            stored.searchScrollStates.mapValues { (_, record) ->
                SearchScrollState(
                    firstVisibleItemIndex = record.firstVisibleItemIndex,
                    firstVisibleItemOffsetPx = record.firstVisibleItemOffsetPx,
                )
            }
        )
        viewerContextFlow.value = stored.viewerLaunchContext?.toDomain()
    }

    override suspend fun setLastTab(route: String) {
        mutex.withLock {
            lastTab = route
            persist()
        }
    }

    override suspend fun getLastTab(): String? {
        return lastTab
    }

    override suspend fun setSearchScrollState(queryHash: String, state: SearchScrollState) {
        mutex.withLock {
            scrollStates[queryHash] = state
            persist()
        }
    }

    override suspend fun getSearchScrollState(queryHash: String): SearchScrollState? {
        return scrollStates[queryHash]
    }

    override fun observeViewerLaunchContext(): Flow<ViewerLaunchContext?> {
        return viewerContextFlow
    }

    override suspend fun setViewerLaunchContext(context: ViewerLaunchContext?) {
        mutex.withLock {
            viewerContextFlow.value = context
            persist()
        }
    }

    private fun persist() {
        writeJson(
            storageFile,
            UiRestoreStoreFile(
                lastTab = lastTab,
                searchScrollStates = scrollStates.mapValues { (_, state) ->
                    SearchScrollStateRecord(
                        firstVisibleItemIndex = state.firstVisibleItemIndex,
                        firstVisibleItemOffsetPx = state.firstVisibleItemOffsetPx,
                    )
                },
                viewerLaunchContext = viewerContextFlow.value?.let(ViewerLaunchContextRecord::fromDomain),
            ),
        )
    }
}

class FileBackedLikesRepository(
    baseDirectory: File,
) : LikesRepository {
    private val mutex = Mutex()
    private val storageFile = baseDirectory.resolve("likes_store.json")
    private val likesFlow = MutableStateFlow<Map<String, Map<PostId, LikedPost>>>(emptyMap())

    init {
        storageFile.parentFile?.mkdirs()
        val stored = readJson(storageFile, LikesStoreFile())
        val grouped = mutableMapOf<String, MutableMap<PostId, LikedPost>>()
        stored.likes.orEmpty().forEach { record ->
            val source = record.source
                ?.let { name -> runCatching { SourceKey.valueOf(name) }.getOrNull() }
                ?: return@forEach
            val sourcePostId = record.sourcePostId?.trim().orEmpty()
            if (sourcePostId.isBlank()) return@forEach
            val profileId = parseStoredProfileId(
                profileId = record.profileId,
                legacyProfile = record.profile,
            )
            val postId = PostId(source = source, sourcePostId = sourcePostId)
            grouped.getOrPut(profileId) { mutableMapOf() }[postId] = LikedPost(
                profileId = profileId,
                postId = postId,
                likedAtEpochMs = record.likedAtEpochMs ?: System.currentTimeMillis(),
                tags = normalizeLikedTags(record.tags.orEmpty()),
            )
        }
        likesFlow.value = grouped.mapValues { (_, likes) -> likes.toMap() }
    }

    override fun observeLikes(profileId: String): Flow<List<LikedPost>> {
        return likesFlow.map { byProfile ->
            byProfile[profileId]
                .orEmpty()
                .values
                .sortedByDescending { it.likedAtEpochMs }
        }
    }

    override fun observeLikedPostIds(profileId: String): Flow<Set<PostId>> {
        return likesFlow.map { byProfile ->
            byProfile[profileId]
                .orEmpty()
                .keys
        }
    }

    override suspend fun toggleLike(profileId: String, postId: PostId, tags: List<String>): Boolean {
        return mutex.withLock {
            val profileLikes = likesFlow.value[profileId].orEmpty().toMutableMap()
            val existing = profileLikes[postId]
            val nowLiked = if (existing == null) {
                profileLikes[postId] = LikedPost(
                    profileId = profileId,
                    postId = postId,
                    likedAtEpochMs = System.currentTimeMillis(),
                    tags = normalizeLikedTags(tags),
                )
                true
            } else {
                profileLikes -= postId
                false
            }
            likesFlow.value = likesFlow.value + (profileId to profileLikes)
            persist()
            nowLiked
        }
    }

    override suspend fun clearLikes(profileId: String) {
        mutex.withLock {
            likesFlow.value = likesFlow.value - profileId
            persist()
        }
    }

    private fun persist() {
        val flattened = likesFlow.value
            .entries
            .flatMap { (profileId, likes) ->
                likes.values.map { liked ->
                    LikedPostRecord.fromDomain(profileId = profileId, liked = liked)
                }
            }
            .sortedByDescending { it.likedAtEpochMs ?: Long.MIN_VALUE }
        writeJson(storageFile, LikesStoreFile(likes = flattened))
    }
}

private data class CodexStoreFile(
    val codices: List<CodexRecord> = emptyList(),
    val items: Map<String, List<CodexItemRecord>> = emptyMap(),
    val posts: List<PostRecord> = emptyList(),
)

private data class CodexRecord(
    val codexId: String,
    val name: String,
    val createdAtEpochMs: Long,
) {
    fun toDomain(): Codex {
        return Codex(codexId = codexId, name = name, createdAtEpochMs = createdAtEpochMs)
    }

    companion object {
        fun fromDomain(codex: Codex): CodexRecord {
            return CodexRecord(codexId = codex.codexId, name = codex.name, createdAtEpochMs = codex.createdAtEpochMs)
        }
    }
}

private data class CodexItemRecord(
    val codexId: String,
    val source: String,
    val sourcePostId: String,
    val savedAtEpochMs: Long,
) {
    fun toDomainOrNull(): CodexItem? {
        val resolvedSource = source.toSourceKeyOrNull() ?: return null
        return CodexItem(
            codexId = codexId,
            postId = PostId(source = resolvedSource, sourcePostId = sourcePostId),
            savedAtEpochMs = savedAtEpochMs,
        )
    }

    companion object {
        fun fromDomain(item: CodexItem): CodexItemRecord {
            return CodexItemRecord(
                codexId = item.codexId,
                source = item.postId.source.name,
                sourcePostId = item.postId.sourcePostId,
                savedAtEpochMs = item.savedAtEpochMs,
            )
        }
    }
}

private data class PostRecord(
    val source: String,
    val sourcePostId: String,
    val previewUrl: String?,
    val previewLocalPath: String?,
    val previewMime: String?,
    val previewProgressiveUrls: List<String>? = null,
    val previewIsAnimated: Boolean? = null,
    val fullUrl: String?,
    val fullLocalPath: String?,
    val fullMime: String?,
    val fullProgressiveUrls: List<String>? = null,
    val fullIsAnimated: Boolean? = null,
    val pageUrl: String?,
    val width: Int?,
    val height: Int?,
    val canonicalTags: List<String>,
    val rawTags: List<String>,
    val authorName: String?,
    val createdAtEpochMs: Long?,
    val media: List<ImageRefRecord?>? = null,
    val title: String? = null,
    val creatorProfile: CreatorProfileRecord? = null,
    val durationMs: Long? = null,
    val mediaCount: Int? = null,
    val taxonomy: List<PostTaxonomyTermRecord?>? = null,
    val creatorProfiles: List<CreatorProfileRecord?>? = null,
) {
    fun toDomainOrNull(): Post? {
        val resolvedSource = source.toSourceKeyOrNull() ?: return null
        val resolvedCreatorProfile = creatorProfile?.toDomainOrNull()
        return Post(
            id = PostId(
                source = resolvedSource,
                sourcePostId = sourcePostId,
            ),
            preview = ImageRef(
                url = previewUrl,
                localPath = previewLocalPath,
                mime = previewMime,
                progressiveUrls = previewProgressiveUrls.orEmpty(),
                isAnimated = previewIsAnimated ?: false,
            ),
            full = if (
                fullUrl == null &&
                fullLocalPath == null &&
                fullMime == null &&
                fullProgressiveUrls.isNullOrEmpty() &&
                fullIsAnimated != true
            ) {
                null
            } else {
                ImageRef(
                    url = fullUrl,
                    localPath = fullLocalPath,
                    mime = fullMime,
                    progressiveUrls = fullProgressiveUrls.orEmpty(),
                    isAnimated = fullIsAnimated ?: false,
                )
            },
            pageUrl = pageUrl,
            width = width,
            height = height,
            canonicalTags = canonicalTags,
            rawTags = rawTags,
            authorName = authorName,
            createdAtEpochMs = createdAtEpochMs,
            media = media.orEmpty().mapNotNull { record -> record?.toDomain() },
            title = title,
            creatorProfile = resolvedCreatorProfile,
            durationMs = durationMs,
            mediaCount = mediaCount,
            taxonomy = taxonomy
                ?.mapNotNull { record -> record?.toDomainOrNull() }
                ?: canonicalTags.map { value -> PostTaxonomyTerm(value = value) },
            creatorProfiles = creatorProfiles
                ?.mapNotNull { record -> record?.toDomainOrNull() }
                ?: listOfNotNull(resolvedCreatorProfile),
        )
    }

    companion object {
        fun fromDomain(post: Post): PostRecord {
            return PostRecord(
                source = post.id.source.name,
                sourcePostId = post.id.sourcePostId,
                previewUrl = post.preview.url,
                previewLocalPath = post.preview.localPath,
                previewMime = post.preview.mime,
                previewProgressiveUrls = post.preview.progressiveUrls,
                previewIsAnimated = post.preview.isAnimated,
                fullUrl = post.full?.url,
                fullLocalPath = post.full?.localPath,
                fullMime = post.full?.mime,
                fullProgressiveUrls = post.full?.progressiveUrls,
                fullIsAnimated = post.full?.isAnimated,
                pageUrl = post.pageUrl,
                width = post.width,
                height = post.height,
                canonicalTags = post.canonicalTags,
                rawTags = post.rawTags,
                authorName = post.authorName,
                createdAtEpochMs = post.createdAtEpochMs,
                media = post.media.map(ImageRefRecord::fromDomain),
                title = post.title,
                creatorProfile = post.creatorProfile?.let(CreatorProfileRecord::fromDomain),
                durationMs = post.durationMs,
                mediaCount = post.mediaCount,
                taxonomy = post.taxonomy.map(PostTaxonomyTermRecord::fromDomain),
                creatorProfiles = post.creatorProfiles.map(CreatorProfileRecord::fromDomain),
            )
        }
    }
}

private data class PostTaxonomyTermRecord(
    val value: String? = null,
    val facet: String? = null,
    val sourceNamespace: String? = null,
) {
    fun toDomainOrNull(): PostTaxonomyTerm? {
        val resolvedValue = value?.trim()?.takeIf(String::isNotBlank) ?: return null
        val resolvedFacet = facet.toSearchFacetOrNull() ?: return null
        return PostTaxonomyTerm(
            value = resolvedValue,
            facet = resolvedFacet,
            sourceNamespace = sourceNamespace?.trim()?.takeIf(String::isNotBlank),
        )
    }

    companion object {
        fun fromDomain(term: PostTaxonomyTerm): PostTaxonomyTermRecord {
            return PostTaxonomyTermRecord(
                value = term.value,
                facet = term.facet.name,
                sourceNamespace = term.sourceNamespace,
            )
        }
    }
}

private data class CreatorProfileRecord(
    val source: String,
    val displayName: String,
    val profileId: String? = null,
    val profileUrl: String? = null,
    val uploadsQuery: String? = null,
) {
    fun toDomainOrNull(): CreatorProfile? {
        val resolvedSource = source.toSourceKeyOrNull() ?: return null
        return CreatorProfile(
            source = resolvedSource,
            displayName = displayName,
            profileId = profileId,
            profileUrl = profileUrl,
            uploadsQuery = uploadsQuery,
        )
    }

    companion object {
        fun fromDomain(profile: CreatorProfile): CreatorProfileRecord {
            return CreatorProfileRecord(
                source = profile.source.name,
                displayName = profile.displayName,
                profileId = profile.profileId,
                profileUrl = profile.profileUrl,
                uploadsQuery = profile.uploadsQuery,
            )
        }
    }
}

private data class ImageRefRecord(
    val url: String?,
    val localPath: String?,
    val mime: String?,
    val progressiveUrls: List<String>? = null,
    val isAnimated: Boolean? = null,
) {
    fun toDomain(): ImageRef {
        return ImageRef(
            url = url,
            localPath = localPath,
            mime = mime,
            progressiveUrls = progressiveUrls.orEmpty(),
            isAnimated = isAnimated ?: false,
        )
    }

    companion object {
        fun fromDomain(ref: ImageRef): ImageRefRecord {
            return ImageRefRecord(
                url = ref.url,
                localPath = ref.localPath,
                mime = ref.mime,
                progressiveUrls = ref.progressiveUrls,
                isAnimated = ref.isAnimated,
            )
        }
    }
}

private data class QueryStoreFile(
    val queries: Map<String, QueryRecord> = emptyMap(),
    val scrollOffsets: Map<String, Int> = emptyMap(),
)

private data class QueryRecord(
    val modeType: String,
    val modeSource: String?,
    val includeTags: List<String>,
    val excludeTags: List<String>,
    val sort: String,
    val dateFromEpochMs: Long?,
    val dateToEpochMs: Long?,
    val minScore: Int?,
    val includeTerms: List<SearchTermRecord?>? = null,
    val excludeTerms: List<SearchTermRecord?>? = null,
) {
    fun toDomain(): Query {
        val mode = when (modeType) {
            "unified" -> QueryMode.Unified
            "source" -> modeSource.toSourceKeyOrNull()?.let(QueryMode::Source) ?: QueryMode.Unified
            else -> QueryMode.Unified
        }
        return Query(
            mode = mode,
            includeTerms = includeTerms
                ?.mapNotNull { record -> record?.toDomainOrNull() }
                ?: includeTags.orEmpty().map { value -> SearchTerm(value = value) },
            excludeTerms = excludeTerms
                ?.mapNotNull { record -> record?.toDomainOrNull() }
                ?: excludeTags.orEmpty().map { value -> SearchTerm(value = value) },
            sort = sort.toSortModeOrDefault(),
            dateRange = if (dateFromEpochMs == null && dateToEpochMs == null) null else DateRange(dateFromEpochMs, dateToEpochMs),
            minScore = minScore,
        )
    }

    companion object {
        fun fromDomain(query: Query): QueryRecord {
            val modeType: String
            val modeSource: String?
            when (val mode = query.mode) {
                QueryMode.Unified -> {
                    modeType = "unified"
                    modeSource = null
                }
                is QueryMode.Source -> {
                    modeType = "source"
                    modeSource = mode.source.name
                }
            }
            return QueryRecord(
                modeType = modeType,
                modeSource = modeSource,
                includeTags = query.includeTags,
                excludeTags = query.excludeTags,
                sort = query.sort.name,
                dateFromEpochMs = query.dateRange?.fromEpochMs,
                dateToEpochMs = query.dateRange?.toEpochMs,
                minScore = query.minScore,
                includeTerms = query.includeTerms.map(SearchTermRecord::fromDomain),
                excludeTerms = query.excludeTerms.map(SearchTermRecord::fromDomain),
            )
        }
    }
}

private data class SearchTermRecord(
    val value: String? = null,
    val facet: String? = null,
    val sourceNamespace: String? = null,
) {
    fun toDomainOrNull(): SearchTerm? {
        val resolvedValue = value?.trim()?.takeIf(String::isNotBlank) ?: return null
        val resolvedFacet = facet.toSearchFacetOrNull() ?: return null
        return SearchTerm(
            value = resolvedValue,
            facet = resolvedFacet,
            sourceNamespace = sourceNamespace?.trim()?.takeIf(String::isNotBlank),
        )
    }

    companion object {
        fun fromDomain(term: SearchTerm): SearchTermRecord {
            return SearchTermRecord(
                value = term.value,
                facet = term.facet.name,
                sourceNamespace = term.sourceNamespace,
            )
        }
    }
}

private data class RecentsStoreFile(
    val watchedPosts: List<RecentPostRecord>? = null,
    val searches: List<RecentSearchRecord>? = null,
)

private data class RecentPostRecord(
    val post: PostRecord? = null,
    val viewedAtEpochMs: Long? = null,
    val origin: String? = null,
    val originQueryHash: String? = null,
) {
    fun toDomainOrNull(): RecentPostEntry? {
        val loadedPost = post?.toDomainOrNull() ?: return null
        val loadedOrigin = origin
            ?.let { value -> runCatching { ViewerStreamSource.valueOf(value) }.getOrNull() }
            ?: ViewerStreamSource.SEARCH
        return RecentPostEntry(
            post = loadedPost,
            viewedAtEpochMs = viewedAtEpochMs ?: 0L,
            origin = loadedOrigin,
            originQueryHash = originQueryHash?.takeIf(String::isNotBlank),
        )
    }

    companion object {
        fun fromDomain(entry: RecentPostEntry): RecentPostRecord {
            return RecentPostRecord(
                post = PostRecord.fromDomain(entry.post),
                viewedAtEpochMs = entry.viewedAtEpochMs,
                origin = entry.origin.name,
                originQueryHash = entry.originQueryHash,
            )
        }
    }
}

private data class RecentSearchRecord(
    val query: QueryRecord? = null,
    val queryHash: String? = null,
    val searchedAtEpochMs: Long? = null,
) {
    fun toDomainOrNull(): RecentSearchEntry? {
        val normalizedHash = queryHash?.trim().orEmpty()
        if (normalizedHash.isBlank()) return null
        val loadedQuery = query?.toDomain() ?: return null
        return RecentSearchEntry(
            query = loadedQuery,
            queryHash = normalizedHash,
            searchedAtEpochMs = searchedAtEpochMs ?: 0L,
        )
    }

    companion object {
        fun fromDomain(entry: RecentSearchEntry): RecentSearchRecord {
            return RecentSearchRecord(
                query = QueryRecord.fromDomain(entry.query),
                queryHash = entry.queryHash,
                searchedAtEpochMs = entry.searchedAtEpochMs,
            )
        }
    }
}

private data class SettingsStoreFile(
    val enabledSources: List<String> = SourceKey.entries.map { it.name },
    val sourceWeights: Map<String, Double> = SourceRuntimeSettings().sourceWeights.mapKeys { it.key.name },
    val cacheFullImageOnSave: Boolean = false,
    val resolveUnknownAnimatedDurations: Boolean = true,
    val invertMultiImageScrollDirection: Boolean = false,
    val scenarioPreset: String = ScenarioPreset.NORMAL.name,
    val lastSelectedTabRoute: String = "search",
    val recommendationProfiles: List<RecommendationProfileRecord>? = null,
    val activeProfileId: String? = null,
    val activeProfile: String? = null,
    val forYouBlacklistByProfile: Map<String, List<ForYouBlacklistEntryRecord>>? = null,
    val favoriteTagsByProfile: Map<String, List<FavoriteTagEntryRecord>>? = null,
    val providerHealth: List<ProviderHealthSnapshotRecord>? = null,
) {
    fun toDomain(): AppSettings {
        val runtime = SourceRuntimeSettings(
            enabledSources = enabledSources.mapNotNull { runCatching { SourceKey.valueOf(it) }.getOrNull() }.toSet(),
            sourceWeights = sourceWeights.mapNotNull { (key, value) ->
                runCatching { SourceKey.valueOf(key) }.getOrNull()?.let { source -> source to value }
            }.toMap(),
        )
        val profiles = recommendationProfiles
            ?.mapNotNull { it.toDomainOrNull() }
            .orEmpty()
            .ifEmpty { defaultRecommendationProfiles() }
        val resolvedActiveProfileId = activeProfileId
            ?.trim()
            ?.takeIf { value -> profiles.any { profile -> profile.profileId == value } }
            ?: parseStoredProfileId(
                profileId = activeProfile,
                legacyProfile = activeProfile,
            ).takeIf { value -> profiles.any { profile -> profile.profileId == value } }
            ?: profiles.first().profileId
        return normalizeSettings(
            AppSettings(
                runtime = runtime,
                cache = CacheSettings(cacheFullImageOnSave = cacheFullImageOnSave),
                contentFilters = ContentFilterSettings(
                    resolveUnknownAnimatedDurations = resolveUnknownAnimatedDurations,
                ),
                viewer = ViewerSettings(
                    invertMultiImageScrollDirection = invertMultiImageScrollDirection,
                ),
                scenarioPreset = runCatching { ScenarioPreset.valueOf(scenarioPreset) }.getOrDefault(ScenarioPreset.NORMAL),
                lastSelectedTabRoute = lastSelectedTabRoute,
                recommendationProfiles = profiles,
                activeProfileId = resolvedActiveProfileId,
                forYouBlacklistByProfile = forYouBlacklistByProfile
                    .orEmpty()
                    .mapValues { (_, entries) ->
                        entries.mapNotNull { entry -> entry.toDomainOrNull() }
                    },
                favoriteTagsByProfile = favoriteTagsByProfile
                    .orEmpty()
                    .mapValues { (_, entries) ->
                        entries.mapNotNull { entry -> entry.toDomainOrNull() }
                    },
                providerHealth = providerHealth
                    .orEmpty()
                    .mapNotNull { record -> record.toDomainOrNull()?.let { snapshot -> snapshot.source to snapshot } }
                    .toMap(),
            )
        )
    }

    companion object {
        fun fromDomain(settings: AppSettings): SettingsStoreFile {
            return SettingsStoreFile(
                enabledSources = settings.runtime.enabledSources.map { it.name },
                sourceWeights = settings.runtime.sourceWeights.mapKeys { it.key.name },
                cacheFullImageOnSave = settings.cache.cacheFullImageOnSave,
                resolveUnknownAnimatedDurations = settings.contentFilters.resolveUnknownAnimatedDurations,
                invertMultiImageScrollDirection = settings.viewer.invertMultiImageScrollDirection,
                scenarioPreset = settings.scenarioPreset.name,
                lastSelectedTabRoute = settings.lastSelectedTabRoute,
                recommendationProfiles = settings.recommendationProfiles.map { profile ->
                    RecommendationProfileRecord.fromDomain(profile)
                },
                activeProfileId = settings.activeProfileId,
                activeProfile = null,
                forYouBlacklistByProfile = settings.forYouBlacklistByProfile
                    .mapValues { (_, entries) ->
                        entries.map { entry -> ForYouBlacklistEntryRecord.fromDomain(entry) }
                    },
                favoriteTagsByProfile = settings.favoriteTagsByProfile
                    .mapValues { (_, entries) ->
                        entries.map { entry -> FavoriteTagEntryRecord.fromDomain(entry) }
                    },
                providerHealth = settings.providerHealth.values
                    .sortedBy { snapshot -> snapshot.source.name }
                    .map(ProviderHealthSnapshotRecord::fromDomain),
            )
        }
    }
}

private data class ProviderHealthSnapshotRecord(
    val source: String? = null,
    val status: String? = null,
    val checkedAtEpochMs: Long? = null,
    val latencyMs: Long? = null,
    val failureReason: String? = null,
    val message: String? = null,
) {
    fun toDomainOrNull(): ProviderHealthSnapshot? {
        val resolvedSource = source
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { value -> runCatching { SourceKey.valueOf(value) }.getOrNull() }
            ?: return null
        val resolvedStatus = status
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { value -> runCatching { ProviderHealthSnapshotStatus.valueOf(value) }.getOrNull() }
            ?: ProviderHealthSnapshotStatus.UNKNOWN
        return ProviderHealthSnapshot(
            source = resolvedSource,
            status = resolvedStatus,
            checkedAtEpochMs = checkedAtEpochMs ?: 0L,
            latencyMs = latencyMs,
            failureReason = failureReason?.takeIf(String::isNotBlank),
            message = message?.takeIf(String::isNotBlank),
        )
    }

    companion object {
        fun fromDomain(snapshot: ProviderHealthSnapshot): ProviderHealthSnapshotRecord {
            return ProviderHealthSnapshotRecord(
                source = snapshot.source.name,
                status = snapshot.status.name,
                checkedAtEpochMs = snapshot.checkedAtEpochMs,
                latencyMs = snapshot.latencyMs,
                failureReason = snapshot.failureReason,
                message = snapshot.message,
            )
        }
    }
}

private data class FavoriteTagEntryRecord(
    val source: String? = null,
    val tag: String? = null,
) {
    fun toDomainOrNull(): FavoriteTagEntry? {
        val resolvedSource = source
            ?.trim()
            ?.takeIf { value -> value.isNotEmpty() }
            ?.let { value -> runCatching { SourceKey.valueOf(value) }.getOrNull() }
            ?: return null
        val normalizedTag = normalizeFavoriteTagForStorage(resolvedSource, tag.orEmpty())
        if (normalizedTag.isBlank()) return null
        return FavoriteTagEntry(
            source = resolvedSource,
            tag = normalizedTag,
        )
    }

    companion object {
        fun fromDomain(entry: FavoriteTagEntry): FavoriteTagEntryRecord {
            return FavoriteTagEntryRecord(
                source = entry.source.name,
                tag = normalizeFavoriteTagForStorage(entry.source, entry.tag),
            )
        }
    }
}

private data class ForYouBlacklistEntryRecord(
    val source: String? = null,
    val tags: List<String>? = null,
) {
    fun toDomainOrNull(): ForYouBlacklistEntry? {
        val resolvedSource = source
            ?.trim()
            ?.takeIf { value -> value.isNotEmpty() }
            ?.let { value -> runCatching { SourceKey.valueOf(value) }.getOrNull() }
            ?: return null
        val normalizedTags = normalizeBlacklistTags(tags.orEmpty())
        if (normalizedTags.isEmpty()) return null
        return ForYouBlacklistEntry(
            source = resolvedSource,
            tags = normalizedTags,
        )
    }

    companion object {
        fun fromDomain(entry: ForYouBlacklistEntry): ForYouBlacklistEntryRecord {
            return ForYouBlacklistEntryRecord(
                source = entry.source.name,
                tags = normalizeBlacklistTags(entry.tags),
            )
        }
    }
}

private data class RecommendationProfileRecord(
    val profileId: String? = null,
    val name: String? = null,
) {
    fun toDomainOrNull(): RecommendationProfile? {
        val id = profileId?.trim().orEmpty()
        val profileName = name?.trim().orEmpty()
        if (id.isBlank() || profileName.isBlank()) return null
        return RecommendationProfile(profileId = id, name = profileName)
    }

    companion object {
        fun fromDomain(profile: RecommendationProfile): RecommendationProfileRecord {
            return RecommendationProfileRecord(
                profileId = profile.profileId,
                name = profile.name,
            )
        }
    }
}

private data class UiRestoreStoreFile(
    val lastTab: String? = null,
    val searchScrollStates: Map<String, SearchScrollStateRecord> = emptyMap(),
    val viewerLaunchContext: ViewerLaunchContextRecord? = null,
)

private data class SearchScrollStateRecord(
    val firstVisibleItemIndex: Int,
    val firstVisibleItemOffsetPx: Int,
)

private data class ViewerLaunchContextRecord(
    val queryHash: String,
    val startIndex: Int,
    val streamSource: String,
    val scrollOffsetHint: Int,
) {
    fun toDomain(): ViewerLaunchContext {
        return ViewerLaunchContext(
            queryHash = queryHash,
            startIndex = startIndex,
            streamSource = runCatching { ViewerStreamSource.valueOf(streamSource) }.getOrDefault(ViewerStreamSource.SEARCH),
            scrollOffsetHint = scrollOffsetHint,
        )
    }

    companion object {
        fun fromDomain(context: ViewerLaunchContext): ViewerLaunchContextRecord {
            return ViewerLaunchContextRecord(
                queryHash = context.queryHash,
                startIndex = context.startIndex,
                streamSource = context.streamSource.name,
                scrollOffsetHint = context.scrollOffsetHint,
            )
        }
    }
}

private data class LikesStoreFile(
    val likes: List<LikedPostRecord>? = null,
)

private data class LikedPostRecord(
    val profileId: String? = null,
    val profile: String? = null,
    val source: String? = null,
    val sourcePostId: String? = null,
    val likedAtEpochMs: Long? = null,
    val tags: List<String>? = null,
) {
    companion object {
        fun fromDomain(profileId: String, liked: LikedPost): LikedPostRecord {
            return LikedPostRecord(
                profileId = profileId,
                profile = null,
                source = liked.postId.source.name,
                sourcePostId = liked.postId.sourcePostId,
                likedAtEpochMs = liked.likedAtEpochMs,
                tags = liked.tags,
            )
        }
    }
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
    val profileIds = normalizedProfiles.mapTo(mutableSetOf()) { profile -> profile.profileId }
    val normalizedBlacklist = settings.forYouBlacklistByProfile
        .mapNotNull { (profileId, entries) ->
            val normalizedProfileId = profileId.trim()
            if (normalizedProfileId !in profileIds) return@mapNotNull null
            val normalizedEntries = entries
                .asSequence()
                .mapNotNull { entry ->
                    val normalizedTags = normalizeBlacklistTags(entry.tags)
                    if (normalizedTags.isEmpty()) {
                        null
                    } else {
                        ForYouBlacklistEntry(source = entry.source, tags = normalizedTags)
                    }
                }
                .distinctBy { entry -> "${entry.source.name}:${entry.tags.joinToString("+")}" }
                .toList()
            normalizedProfileId to normalizedEntries
        }
        .toMap()
        .filterValues { entries -> entries.isNotEmpty() }
    val normalizedFavoriteTags = settings.favoriteTagsByProfile
        .mapNotNull { (profileId, entries) ->
            val normalizedProfileId = profileId.trim()
            if (normalizedProfileId !in profileIds) return@mapNotNull null
            val normalizedEntries = entries
                .asSequence()
                .mapNotNull { entry ->
                    val normalizedTag = normalizeFavoriteTagForStorage(entry.source, entry.tag)
                    if (normalizedTag.isBlank()) {
                        null
                    } else {
                        FavoriteTagEntry(source = entry.source, tag = normalizedTag)
                    }
                }
                .distinctBy { entry -> "${entry.source.name}:${sourceTagKey(entry.source, entry.tag)}" }
                .toList()
            normalizedProfileId to normalizedEntries
        }
        .toMap()
        .filterValues { entries -> entries.isNotEmpty() }
    val normalizedProviderHealth = settings.providerHealth
        .mapValues { (source, snapshot) ->
            snapshot.copy(source = source)
        }
        .filterValues { snapshot -> snapshot.checkedAtEpochMs >= 0L }
    return settings.copy(
        runtime = settings.runtime.copy(sourceWeights = normalizedWeights),
        recommendationProfiles = normalizedProfiles,
        activeProfileId = activeProfileId,
        forYouBlacklistByProfile = normalizedBlacklist,
        favoriteTagsByProfile = normalizedFavoriteTags,
        providerHealth = normalizedProviderHealth,
    )
}

private fun String?.toSourceKeyOrNull(): SourceKey? {
    return this
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let { value -> runCatching { SourceKey.valueOf(value) }.getOrNull() }
}

private fun String?.toSortModeOrDefault(): SortMode {
    return this
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let { value -> runCatching { SortMode.valueOf(value) }.getOrNull() }
        ?: SortMode.TOP
}

private fun String?.toSearchFacetOrNull(): SearchFacet? {
    val normalized = this?.trim()?.takeIf(String::isNotBlank) ?: return SearchFacet.TAG
    return runCatching { SearchFacet.valueOf(normalized) }.getOrNull()
}

private fun normalizeBlacklistTags(tags: List<String>): List<String> {
    return tags
        .asSequence()
        .map { it.trim().lowercase() }
        .filter { it.isNotBlank() }
        .distinct()
        .sorted()
        .toList()
}

private fun parseStoredProfileId(
    profileId: String?,
    legacyProfile: String?,
): String {
    val normalizedProfileId = profileId?.trim().orEmpty()
    if (normalizedProfileId.isNotBlank()) {
        return when (normalizedProfileId) {
            "USER_1" -> "profile-main"
            "USER_2" -> "profile-alt"
            else -> normalizedProfileId
        }
    }

    return when (legacyProfile?.trim()) {
        "USER_1" -> "profile-main"
        "USER_2" -> "profile-alt"
        else -> "profile-main"
    }
}

private fun normalizeWeights(
    enabledSources: Set<SourceKey>,
    rawWeights: Map<SourceKey, Double>,
): Map<SourceKey, Double> {
    if (enabledSources.isEmpty()) return emptyMap()
    val defaults = SourceRuntimeSettings().sourceWeights
    val positiveWeights = enabledSources.associateWith { source ->
        (rawWeights[source] ?: defaults[source] ?: 1.0).coerceAtLeast(0.0)
    }
    val total = positiveWeights.values.sum().takeIf { it > 0.0 } ?: enabledSources.size.toDouble()
    return positiveWeights.mapValues { (_, weight) -> weight / total }
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

private fun List<RecentPostEntry>.dedupeRecentWatched(): List<RecentPostEntry> {
    return sortedByDescending { entry -> entry.viewedAtEpochMs }
        .distinctBy { entry -> entry.post.id }
}

private fun List<RecentSearchEntry>.dedupeRecentSearches(): List<RecentSearchEntry> {
    return sortedByDescending { entry -> entry.searchedAtEpochMs }
        .distinctBy { entry -> entry.queryHash }
}

private fun <T> readJson(file: File, fallback: T, clazz: Class<T>): T {
    if (!file.exists()) {
        return fallback
    }
    return runCatching {
        json.fromJson(file.readText(), clazz) ?: fallback
    }.getOrDefault(fallback)
}

private inline fun <reified T> readJson(file: File, fallback: T): T {
    return readJson(file, fallback, T::class.java)
}

private fun <T> writeJson(file: File, payload: T) {
    val parent = file.parentFile
    parent?.mkdirs()
    val tempFile = File.createTempFile("${file.name}.", ".tmp", parent ?: File("."))
    try {
        tempFile.writeText(json.toJson(payload))
        runCatching {
            Files.move(tempFile.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        }.getOrElse {
            Files.move(tempFile.toPath(), file.toPath(), REPLACE_EXISTING)
        }
    } finally {
        if (tempFile.exists()) {
            tempFile.delete()
        }
    }
}
