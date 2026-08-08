package com.theoriacodex.data.repository

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import com.theoriacodex.data.storage.AtomicJsonFileStore
import com.theoriacodex.data.storage.LegacyJsonRecoveryRegistry
import com.theoriacodex.data.storage.PostStorageCodec
import com.theoriacodex.data.storage.PostStorageRecord
import com.theoriacodex.data.storage.QueryStorageCodec
import com.theoriacodex.data.storage.QueryStorageRecord
import com.theoriacodex.data.storage.mutateAndPersistWithRollback
import com.theoriacodex.domain.model.Codex
import com.theoriacodex.domain.model.CodexItem
import com.theoriacodex.domain.model.DateRange
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext


class FileBackedCodexRepository(
    baseDirectory: File,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CodexRepository {
    private val mutex = Mutex()
    private val storageFile = baseDirectory.resolve("codex_store.json")
    private val fileStore = AtomicJsonFileStore(ioDispatcher = ioDispatcher)
    private val codicesFlow = MutableStateFlow<List<Codex>>(emptyList())
    private val itemsFlow = MutableStateFlow<Map<String, List<CodexItem>>>(emptyMap())
    private val postsFlow = MutableStateFlow<Map<PostId, Post>>(emptyMap())

    init {
        val stored = runBlocking { fileStore.read(storageFile, CodexStoreFile()) }
        codicesFlow.value = stored.codices.map { it.toDomain() }
        itemsFlow.value = stored.items.mapValues { entry -> entry.value.mapNotNull { it.toDomainOrNull() } }
        postsFlow.value = stored.posts.mapNotNull(PostStorageCodec::decode).associate { post ->
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
                    commitMutation {
                        codicesFlow.value = codicesFlow.value.map { codex ->
                            if (codex.codexId == codexId) updated else codex
                        }
                        updated
                    }
                }
            } else {
                val codex = Codex(
                    codexId = codexId,
                    name = resolvedName,
                    createdAtEpochMs = System.currentTimeMillis(),
                )
                commitMutation {
                    codicesFlow.value = codicesFlow.value + codex
                    codex
                }
            }
        }
    }

    override suspend fun createCodex(name: String): Codex {
        return mutex.withLock {
            val resolvedName = RepositoryPolicies.resolveUniqueCodexName(
                requestedName = name,
                existingCodices = codicesFlow.value,
            )
            val codex = Codex(
                codexId = UUID.randomUUID().toString(),
                name = resolvedName,
                createdAtEpochMs = System.currentTimeMillis(),
            )
            commitMutation {
                codicesFlow.value = codicesFlow.value + codex
                codex
            }
        }
    }

    override suspend fun reorderCodex(codexId: String, targetIndex: Int) {
        mutex.withLock {
            val reordered = RepositoryPolicies.reorderCodices(
                codices = codicesFlow.value,
                codexId = codexId,
                targetIndex = targetIndex,
            )
            if (reordered != codicesFlow.value) {
                commitMutation { codicesFlow.value = reordered }
            }
        }
    }

    override suspend fun renameCodex(codexId: String, name: String) {
        mutex.withLock {
            val current = codicesFlow.value
            val existing = current.firstOrNull { it.codexId == codexId } ?: return@withLock
            val resolvedName = RepositoryPolicies.resolveUniqueCodexName(
                requestedName = name,
                existingCodices = current,
                excludeCodexId = codexId,
            )
            if (existing.name == resolvedName) return@withLock
            commitMutation {
                codicesFlow.value = codicesFlow.value.map { codex ->
                    if (codex.codexId == codexId) codex.copy(name = resolvedName) else codex
                }
            }
        }
    }

    override suspend fun deleteCodex(codexId: String) {
        mutex.withLock {
            commitMutation {
                codicesFlow.value = codicesFlow.value.filterNot { it.codexId == codexId }
                itemsFlow.value = itemsFlow.value - codexId
            }
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
            RepositoryPolicies.sortCodexPairs(pairs, sort).map { it.second }
        }
    }

    override suspend fun getPost(postId: PostId): Post? {
        return postsFlow.value[postId]
    }

    override suspend fun addItem(codexId: String, post: Post) {
        mutex.withLock {
            if (codicesFlow.value.none { it.codexId == codexId }) return@withLock
            val existing = itemsFlow.value[codexId].orEmpty()
            val updatedItems = RepositoryPolicies.addCodexItem(
                items = existing,
                codexId = codexId,
                postId = post.id,
                savedAtEpochMs = System.currentTimeMillis(),
            )
            val membershipChanged = updatedItems != existing
            val postChanged = postsFlow.value[post.id] != post
            if (!postChanged && !membershipChanged) return@withLock

            commitMutation {
                if (postChanged) {
                    postsFlow.value = postsFlow.value + (post.id to post)
                }
                if (membershipChanged) {
                    itemsFlow.value = itemsFlow.value + (codexId to updatedItems)
                }
            }
        }
    }

    override suspend fun updatePost(post: Post) {
        mutex.withLock {
            val existing = postsFlow.value[post.id] ?: return@withLock
            if (existing == post) return@withLock
            commitMutation { postsFlow.value = postsFlow.value + (post.id to post) }
        }
    }

    override suspend fun removeItem(codexId: String, sourceKey: SourceKey, sourcePostId: String) {
        mutex.withLock {
            val target = PostId(source = sourceKey, sourcePostId = sourcePostId)
            val existing = itemsFlow.value[codexId].orEmpty()
            val updated = RepositoryPolicies.removeCodexItem(existing, target)
            if (updated != existing) {
                commitMutation { itemsFlow.value = itemsFlow.value + (codexId to updated) }
            }
        }
    }

    override suspend fun removeItems(codexId: String, postIds: Set<PostId>) {
        if (postIds.isEmpty()) return
        mutex.withLock {
            val existing = itemsFlow.value[codexId].orEmpty()
            val updated = existing.filterNot { item -> item.postId in postIds }
            if (updated != existing) {
                commitMutation { itemsFlow.value = itemsFlow.value + (codexId to updated) }
            }
        }
    }

    override suspend fun restoreItems(items: List<CodexItem>, posts: List<Post>) {
        if (items.isEmpty()) return
        mutex.withLock {
            val validCodexIds = codicesFlow.value.mapTo(mutableSetOf(), Codex::codexId)
            val postsBySnapshotId = posts.associateBy(Post::id)
            val restoredItems = itemsFlow.value.toMutableMap()
            val restoredPosts = postsFlow.value.toMutableMap()
            items.groupBy(CodexItem::codexId).forEach { (codexId, snapshots) ->
                if (codexId !in validCodexIds) return@forEach
                val existing = restoredItems[codexId].orEmpty()
                val existingIds = existing.mapTo(mutableSetOf(), CodexItem::postId)
                val additions = snapshots.filter { item ->
                    item.postId !in existingIds && postsBySnapshotId[item.postId] != null
                }
                additions.forEach { item ->
                    restoredPosts.putIfAbsent(item.postId, postsBySnapshotId.getValue(item.postId))
                }
                restoredItems[codexId] = existing + additions
            }
            if (restoredItems != itemsFlow.value || restoredPosts != postsFlow.value) {
                commitMutation {
                    itemsFlow.value = restoredItems
                    postsFlow.value = restoredPosts
                }
            }
        }
    }

    private suspend inline fun <T> commitMutation(mutate: () -> T): T {
        return mutateAndPersistWithRollback(
            snapshot = { Triple(codicesFlow.value, itemsFlow.value, postsFlow.value) },
            restore = { (codices, items, posts) ->
                codicesFlow.value = codices
                itemsFlow.value = items
                postsFlow.value = posts
            },
            mutate = mutate,
            persist = { persist() },
        )
    }

    private suspend fun persist() {
        val toPersist = CodexStoreFile(
            codices = codicesFlow.value.map { CodexRecord.fromDomain(it) },
            items = itemsFlow.value.mapValues { entry -> entry.value.map { CodexItemRecord.fromDomain(it) } },
            posts = postsFlow.value.values.map(PostStorageCodec::encode),
        )
        fileStore.write(storageFile, toPersist)
    }
}

class FileBackedQueryRepository(
    baseDirectory: File,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    recoveryRegistry: LegacyJsonRecoveryRegistry = LegacyJsonRecoveryRegistry(),
) : QueryRepository {
    private val mutex = Mutex()
    private val storageFile = baseDirectory.resolve("query_store.json")
    private val fileStore = AtomicJsonFileStore(ioDispatcher = ioDispatcher)
    private val queriesFlow = MutableStateFlow<Map<String, Query>>(emptyMap())

    init {
        recoveryRegistry.registerStore("Saved searches", storageFile)
        val stored = runBlocking {
            fileStore.read(
                file = storageFile,
                fallback = QueryStoreFile(),
                logicalStore = "Saved searches",
                onRecovery = recoveryRegistry::record,
            )
        }
        queriesFlow.value = stored.queries.mapValues { (_, record) -> QueryStorageCodec.decode(record) }
    }

    override fun observeAppliedQuery(modeKey: String): Flow<Query?> {
        return queriesFlow.map { it[modeKey] }
    }

    override suspend fun upsertAppliedQuery(modeKey: String, query: Query) {
        mutex.withLock {
            commitMutation { queriesFlow.value = queriesFlow.value + (modeKey to query) }
        }
    }

    private suspend inline fun <T> commitMutation(mutate: () -> T): T {
        return mutateAndPersistWithRollback(
            snapshot = { queriesFlow.value },
            restore = { queries -> queriesFlow.value = queries },
            mutate = mutate,
            persist = { persist() },
        )
    }

    private suspend fun persist() {
        val payload = QueryStoreFile(
            queries = queriesFlow.value.mapValues { (_, query) -> QueryStorageCodec.encode(query) },
        )
        fileStore.write(storageFile, payload)
    }
}

class FileBackedSettingsRepository(
    baseDirectory: File,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SettingsRepository {
    private val mutex = Mutex()
    private val storageFile = baseDirectory.resolve("settings_store.json")
    private val fileStore = AtomicJsonFileStore(ioDispatcher = ioDispatcher)
    private val settingsFlow = MutableStateFlow(RepositoryPolicies.normalizeSettings(AppSettings()))

    init {
        val stored = runBlocking {
            fileStore.read(storageFile, SettingsStoreFile.fromDomain(AppSettings()))
        }
        settingsFlow.value = stored.toDomain()
        if (stored.requiresSourceCatalogMigration()) {
            runBlocking { persist() }
        }
    }

    override fun observeSettings(): Flow<AppSettings> {
        return settingsFlow
    }

    override suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        mutateSettings { current ->
            RepositoryPolicies.Result(state = transform(current), value = Unit)
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

    @Deprecated("Last-tab state is owned by UiRestoreRepository; retain this writer only for compatibility.")
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
        updateSettings { current ->
            RepositoryPolicies.mergeProviderHealth(current, snapshots)
        }
    }

    private suspend fun <T> mutateSettings(
        policy: (AppSettings) -> RepositoryPolicies.Result<AppSettings, T>,
    ): T {
        return mutex.withLock {
            val current = settingsFlow.value
            val result = policy(current)
            val normalized = RepositoryPolicies.normalizeSettings(result.state)
            if (normalized == current) return@withLock result.value
            mutateAndPersistWithRollback(
                snapshot = { settingsFlow.value },
                restore = { settings -> settingsFlow.value = settings },
                mutate = {
                    settingsFlow.value = normalized
                    result.value
                },
                persist = { persist() },
            )
        }
    }

    private suspend fun persist() {
        fileStore.write(storageFile, SettingsStoreFile.fromDomain(settingsFlow.value))
    }
}

class FileBackedCacheRepository(
    baseDirectory: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CacheRepository {
    private val mutex = Mutex()
    private val thumbnailDir = baseDirectory.resolve("cache/thumbnails")
    private val fullDir = baseDirectory.resolve("cache/full")
    private val snapshotFlow = MutableStateFlow(CacheSnapshot(thumbnailCount = 0, fullImageCount = 0))

    init {
        snapshotFlow.value = runBlocking {
            withContext(ioDispatcher) {
                thumbnailDir.mkdirs()
                fullDir.mkdirs()
                currentSnapshot()
            }
        }
    }

    override fun observeSnapshot(): Flow<CacheSnapshot> {
        return snapshotFlow
    }

    override suspend fun cacheThumbnail(post: Post) {
        mutex.withLock {
            snapshotFlow.value = withContext(ioDispatcher) {
                writeCachedEntry(
                    targetDirectory = thumbnailDir,
                    key = cacheKey(post.id),
                    localPath = post.preview.localPath,
                    fallbackUrl = post.preview.url,
                )
                currentSnapshot()
            }
        }
    }

    override suspend fun cacheFull(post: Post) {
        mutex.withLock {
            val fullImage = post.full ?: return@withLock
            snapshotFlow.value = withContext(ioDispatcher) {
                writeCachedEntry(
                    targetDirectory = fullDir,
                    key = cacheKey(post.id),
                    localPath = fullImage.localPath,
                    fallbackUrl = fullImage.url,
                )
                currentSnapshot()
            }
        }
    }

    override suspend fun clearThumbnailCache() {
        mutex.withLock {
            snapshotFlow.value = withContext(ioDispatcher) {
                thumbnailDir.deleteRecursively()
                thumbnailDir.mkdirs()
                currentSnapshot()
            }
        }
    }

    override suspend fun clearFullImageCache() {
        mutex.withLock {
            snapshotFlow.value = withContext(ioDispatcher) {
                fullDir.deleteRecursively()
                fullDir.mkdirs()
                currentSnapshot()
            }
        }
    }

    private fun writeCachedEntry(
        targetDirectory: File,
        key: String,
        localPath: String?,
        fallbackUrl: String?,
    ) {
        targetDirectory
            .listFiles()
            .orEmpty()
            .filter { file -> file.isFile && file.name.startsWith("$key.") }
            .forEach(File::delete)

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
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : UiRestoreRepository {
    private val mutex = Mutex()
    private val storageFile = baseDirectory.resolve("ui_restore_store.json")
    private val fileStore = AtomicJsonFileStore(ioDispatcher = ioDispatcher)
    private val viewerContextFlow = MutableStateFlow<ViewerLaunchContext?>(null)
    private val scrollStates = mutableMapOf<String, SearchScrollState>()
    private var settingsSectionExpansion: Map<String, Boolean> = emptyMap()
    private var lastTab: String? = null

    init {
        val stored = runBlocking { fileStore.read(storageFile, UiRestoreStoreFile()) }
        lastTab = stored.lastTab
        scrollStates.putAll(
            stored.searchScrollStates.mapValues { (_, record) ->
                SearchScrollState(
                    firstVisibleItemIndex = record.firstVisibleItemIndex,
                    firstVisibleItemOffsetPx = record.firstVisibleItemOffsetPx,
                )
            }
        )
        settingsSectionExpansion = stored.settingsSectionExpansion
        viewerContextFlow.value = stored.viewerLaunchContext?.toDomain()
    }

    override suspend fun setLastTab(route: String) {
        mutex.withLock {
            commitMutation { lastTab = route }
        }
    }

    override suspend fun getLastTab(): String? {
        return lastTab
    }

    override suspend fun migrateLegacyLastTab(legacyRoute: String?): String? {
        return mutex.withLock {
            lastTab?.let { current -> return@withLock current }
            val migrated = legacyRoute?.trim()?.takeIf { route -> route.isNotEmpty() }
                ?: return@withLock null
            commitMutation {
                lastTab = migrated
                migrated
            }
        }
    }

    override suspend fun setSearchScrollState(queryHash: String, state: SearchScrollState) {
        mutex.withLock {
            commitMutation { scrollStates[queryHash] = state }
        }
    }

    override suspend fun getSearchScrollState(queryHash: String): SearchScrollState? {
        return scrollStates[queryHash]
    }

    override suspend fun setSettingsSectionExpansion(expansion: Map<String, Boolean>) {
        mutex.withLock {
            commitMutation { settingsSectionExpansion = expansion.toMap() }
        }
    }

    override suspend fun getSettingsSectionExpansion(): Map<String, Boolean> {
        return settingsSectionExpansion
    }

    override fun observeViewerLaunchContext(): Flow<ViewerLaunchContext?> {
        return viewerContextFlow
    }

    override suspend fun setViewerLaunchContext(context: ViewerLaunchContext?) {
        mutex.withLock {
            commitMutation { viewerContextFlow.value = context }
        }
    }

    private suspend inline fun <T> commitMutation(mutate: () -> T): T {
        return mutateAndPersistWithRollback(
            snapshot = {
                UiRestoreMemoryState(
                    lastTab = lastTab,
                    scrollStates = scrollStates.toMap(),
                    settingsSectionExpansion = settingsSectionExpansion,
                    viewerLaunchContext = viewerContextFlow.value,
                )
            },
            restore = { state ->
                lastTab = state.lastTab
                scrollStates.clear()
                scrollStates.putAll(state.scrollStates)
                settingsSectionExpansion = state.settingsSectionExpansion
                viewerContextFlow.value = state.viewerLaunchContext
            },
            mutate = mutate,
            persist = { persist() },
        )
    }

    private suspend fun persist() {
        fileStore.write(
            storageFile,
            UiRestoreStoreFile(
                lastTab = lastTab,
                searchScrollStates = scrollStates.mapValues { (_, state) ->
                    SearchScrollStateRecord(
                        firstVisibleItemIndex = state.firstVisibleItemIndex,
                        firstVisibleItemOffsetPx = state.firstVisibleItemOffsetPx,
                    )
                },
                settingsSectionExpansion = settingsSectionExpansion,
                viewerLaunchContext = viewerContextFlow.value?.let(ViewerLaunchContextRecord::fromDomain),
            ),
        )
    }
}

class FileBackedLikesRepository(
    baseDirectory: File,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) : LikesRepository {
    private val mutex = Mutex()
    private val storageFile = baseDirectory.resolve("likes_store.json")
    private val fileStore = AtomicJsonFileStore(ioDispatcher = ioDispatcher)
    private val likesFlow = MutableStateFlow<Map<String, Map<PostId, LikedPost>>>(emptyMap())

    init {
        val stored = runBlocking { fileStore.read(storageFile, LikesStoreFile()) }
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
                likedAtEpochMs = record.likedAtEpochMs ?: clock(),
                tags = RepositoryPolicies.normalizeLikedTags(record.tags.orEmpty()),
            )
        }
        likesFlow.value = grouped.mapValues { (_, likes) -> likes.toMap() }
    }

    override fun observeLikes(profileId: String): Flow<List<LikedPost>> {
        return likesFlow.map { byProfile ->
            byProfile[RepositoryPolicies.normalizeProfileId(profileId)]
                .orEmpty()
                .values
                .sortedByDescending { it.likedAtEpochMs }
        }
    }

    override fun observeLikedPostIds(profileId: String): Flow<Set<PostId>> {
        return likesFlow.map { byProfile ->
            byProfile[RepositoryPolicies.normalizeProfileId(profileId)]
                .orEmpty()
                .keys
        }
    }

    override suspend fun toggleLike(profileId: String, postId: PostId, tags: List<String>): Boolean {
        return mutex.withLock {
            val result = RepositoryPolicies.toggleLike(
                likesByProfile = likesFlow.value,
                profileId = profileId,
                postId = postId,
                tags = tags,
                likedAtEpochMs = clock(),
            )
            if (result.state == likesFlow.value) return@withLock result.value
            commitMutation {
                likesFlow.value = result.state
                result.value
            }
        }
    }

    override suspend fun clearLikes(profileId: String) {
        mutex.withLock {
            val normalizedProfileId = RepositoryPolicies.normalizeProfileId(profileId)
            if (normalizedProfileId !in likesFlow.value) return@withLock
            commitMutation { likesFlow.value = likesFlow.value - normalizedProfileId }
        }
    }

    private suspend inline fun <T> commitMutation(mutate: () -> T): T {
        return mutateAndPersistWithRollback(
            snapshot = { likesFlow.value },
            restore = { likes -> likesFlow.value = likes },
            mutate = mutate,
            persist = { persist() },
        )
    }

    private suspend fun persist() {
        val flattened = likesFlow.value
            .entries
            .flatMap { (profileId, likes) ->
                likes.values.map { liked ->
                    LikedPostRecord.fromDomain(profileId = profileId, liked = liked)
                }
            }
            .sortedByDescending { it.likedAtEpochMs ?: Long.MIN_VALUE }
        fileStore.write(storageFile, LikesStoreFile(likes = flattened))
    }
}

private data class CodexStoreFile(
    @field:SerializedName("codices")
    val codices: List<CodexRecord> = emptyList(),
    @field:SerializedName("items")
    val items: Map<String, List<CodexItemRecord>> = emptyMap(),
    @field:SerializedName("posts")
    val posts: List<PostStorageRecord> = emptyList(),
)

private data class CodexRecord(
    @field:SerializedName("codexId")
    val codexId: String = "",
    @field:SerializedName("name")
    val name: String = "",
    @field:SerializedName("createdAtEpochMs")
    val createdAtEpochMs: Long = 0L,
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
    @field:SerializedName("codexId")
    val codexId: String = "",
    @field:SerializedName("source")
    val source: String = "",
    @field:SerializedName("sourcePostId")
    val sourcePostId: String = "",
    @field:SerializedName("savedAtEpochMs")
    val savedAtEpochMs: Long = 0L,
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

private data class QueryStoreFile(
    @field:SerializedName("queries")
    val queries: Map<String, QueryStorageRecord> = emptyMap(),
)

private const val CURRENT_SOURCE_CATALOG_VERSION = 2

private data class SettingsStoreFile(
    @field:SerializedName("sourceCatalogVersion")
    val sourceCatalogVersion: Int? = null,
    @field:SerializedName("enabledSources")
    val enabledSources: List<String> = SourceKey.entries.map { it.name },
    @field:SerializedName("sourceWeights")
    val sourceWeights: Map<String, Double> = SourceRuntimeSettings().sourceWeights.mapKeys { it.key.name },
    @field:SerializedName("cacheFullImageOnSave")
    val cacheFullImageOnSave: Boolean = false,
    @field:SerializedName("resolveUnknownAnimatedDurations")
    val resolveUnknownAnimatedDurations: Boolean = true,
    @field:SerializedName("invertMultiImageScrollDirection")
    val invertMultiImageScrollDirection: Boolean = false,
    @field:SerializedName("scenarioPreset")
    val scenarioPreset: String = ScenarioPreset.NORMAL.name,
    @field:SerializedName("lastSelectedTabRoute")
    val lastSelectedTabRoute: String = "search",
    @field:SerializedName("recommendationProfiles")
    val recommendationProfiles: List<RecommendationProfileRecord>? = null,
    @field:SerializedName("activeProfileId")
    val activeProfileId: String? = null,
    @field:SerializedName("activeProfile")
    val activeProfile: String? = null,
    @field:SerializedName("forYouBlacklistByProfile")
    val forYouBlacklistByProfile: Map<String, List<ForYouBlacklistEntryRecord>>? = null,
    @field:SerializedName("favoriteTagsByProfile")
    val favoriteTagsByProfile: Map<String, List<FavoriteTagEntryRecord>>? = null,
    @field:SerializedName("providerHealth")
    val providerHealth: List<ProviderHealthSnapshotRecord>? = null,
) {
    fun toDomain(): AppSettings {
        val storedEnabledSources = enabledSources
            .mapNotNull { runCatching { SourceKey.valueOf(it) }.getOrNull() }
            .toSet()
        val migratedEnabledSources = if (requiresSourceCatalogMigration()) {
            storedEnabledSources + SourceKey.HITOMI
        } else {
            storedEnabledSources
        }
        val runtime = SourceRuntimeSettings(
            enabledSources = migratedEnabledSources,
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
        return RepositoryPolicies.normalizeSettings(
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

    fun requiresSourceCatalogMigration(): Boolean {
        return (sourceCatalogVersion ?: 1) < CURRENT_SOURCE_CATALOG_VERSION
    }

    companion object {
        fun fromDomain(settings: AppSettings): SettingsStoreFile {
            return SettingsStoreFile(
                sourceCatalogVersion = CURRENT_SOURCE_CATALOG_VERSION,
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
    @field:SerializedName("source")
    val source: String? = null,
    @field:SerializedName("status")
    val status: String? = null,
    @field:SerializedName("checkedAtEpochMs")
    val checkedAtEpochMs: Long? = null,
    @field:SerializedName("latencyMs")
    val latencyMs: Long? = null,
    @field:SerializedName("failureReason")
    val failureReason: String? = null,
    @field:SerializedName("message")
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
    @field:SerializedName("source")
    val source: String? = null,
    @field:SerializedName("tag")
    val tag: String? = null,
) {
    fun toDomainOrNull(): FavoriteTagEntry? {
        val resolvedSource = source
            ?.trim()
            ?.takeIf { value -> value.isNotEmpty() }
            ?.let { value -> runCatching { SourceKey.valueOf(value) }.getOrNull() }
            ?: return null
        val normalizedTag = RepositoryPolicies.normalizeFavoriteTag(resolvedSource, tag.orEmpty())
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
                tag = RepositoryPolicies.normalizeFavoriteTag(entry.source, entry.tag),
            )
        }
    }
}

private data class ForYouBlacklistEntryRecord(
    @field:SerializedName("source")
    val source: String? = null,
    @field:SerializedName("tags")
    val tags: List<String>? = null,
) {
    fun toDomainOrNull(): ForYouBlacklistEntry? {
        val resolvedSource = source
            ?.trim()
            ?.takeIf { value -> value.isNotEmpty() }
            ?.let { value -> runCatching { SourceKey.valueOf(value) }.getOrNull() }
            ?: return null
        val normalizedTags = RepositoryPolicies.normalizeBlacklistTags(tags.orEmpty())
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
                tags = RepositoryPolicies.normalizeBlacklistTags(entry.tags),
            )
        }
    }
}

private data class RecommendationProfileRecord(
    @field:SerializedName("profileId")
    val profileId: String? = null,
    @field:SerializedName("name")
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

private data class LikesStoreFile(
    @field:SerializedName("likes")
    val likes: List<LikedPostRecord>? = null,
)

private data class LikedPostRecord(
    @field:SerializedName("profileId")
    val profileId: String? = null,
    @field:SerializedName("profile")
    val profile: String? = null,
    @field:SerializedName("source")
    val source: String? = null,
    @field:SerializedName("sourcePostId")
    val sourcePostId: String? = null,
    @field:SerializedName("likedAtEpochMs")
    val likedAtEpochMs: Long? = null,
    @field:SerializedName("tags")
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
