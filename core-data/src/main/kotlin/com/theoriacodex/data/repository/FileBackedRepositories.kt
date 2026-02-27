package com.theoriacodex.data.repository

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.theoriacodex.domain.model.Codex
import com.theoriacodex.domain.model.CodexItem
import com.theoriacodex.domain.model.DateRange
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val json: Gson = GsonBuilder().setPrettyPrinting().create()

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
        itemsFlow.value = stored.items.mapValues { entry -> entry.value.map { it.toDomain() } }
        postsFlow.value = stored.posts.associate { record ->
            val post = record.toDomain()
            post.id to post
        }
    }

    override fun observeCodices(): Flow<List<Codex>> {
        return codicesFlow
    }

    override fun observeCodex(codexId: String): Flow<Codex?> {
        return codicesFlow.map { codices -> codices.firstOrNull { it.codexId == codexId } }
    }

    override suspend fun createCodex(name: String): Codex {
        return mutex.withLock {
            val codex = Codex(
                codexId = UUID.randomUUID().toString(),
                name = name,
                createdAtEpochMs = System.currentTimeMillis(),
            )
            codicesFlow.value = codicesFlow.value + codex
            persist()
            codex
        }
    }

    override suspend fun renameCodex(codexId: String, name: String) {
        mutex.withLock {
            codicesFlow.value = codicesFlow.value.map { existing ->
                if (existing.codexId == codexId) existing.copy(name = name) else existing
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

    override suspend fun setActiveProfile(profile: UserProfile) {
        updateSettings {
            it.copy(activeProfile = profile)
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
                Files.copy(localFile.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING)
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
    private val likesFlow = MutableStateFlow<Map<UserProfile, Map<PostId, LikedPost>>>(emptyMap())

    init {
        storageFile.parentFile?.mkdirs()
        val stored = readJson(storageFile, LikesStoreFile())
        val grouped = mutableMapOf<UserProfile, MutableMap<PostId, LikedPost>>()
        stored.likes.orEmpty().forEach { record ->
            val source = record.source
                ?.let { name -> runCatching { SourceKey.valueOf(name) }.getOrNull() }
                ?: return@forEach
            val sourcePostId = record.sourcePostId?.trim().orEmpty()
            if (sourcePostId.isBlank()) return@forEach
            val profile = record.profile
                ?.let { name -> runCatching { UserProfile.valueOf(name) }.getOrNull() }
                ?: UserProfile.USER_1
            val postId = PostId(source = source, sourcePostId = sourcePostId)
            grouped.getOrPut(profile) { mutableMapOf() }[postId] = LikedPost(
                profile = profile,
                postId = postId,
                likedAtEpochMs = record.likedAtEpochMs ?: System.currentTimeMillis(),
                tags = normalizeLikedTags(record.tags.orEmpty()),
            )
        }
        likesFlow.value = grouped.mapValues { (_, likes) -> likes.toMap() }
    }

    override fun observeLikes(profile: UserProfile): Flow<List<LikedPost>> {
        return likesFlow.map { byProfile ->
            byProfile[profile]
                .orEmpty()
                .values
                .sortedByDescending { it.likedAtEpochMs }
        }
    }

    override fun observeLikedPostIds(profile: UserProfile): Flow<Set<PostId>> {
        return likesFlow.map { byProfile ->
            byProfile[profile]
                .orEmpty()
                .keys
        }
    }

    override suspend fun toggleLike(profile: UserProfile, postId: PostId, tags: List<String>): Boolean {
        return mutex.withLock {
            val profileLikes = likesFlow.value[profile].orEmpty().toMutableMap()
            val existing = profileLikes[postId]
            val nowLiked = if (existing == null) {
                profileLikes[postId] = LikedPost(
                    profile = profile,
                    postId = postId,
                    likedAtEpochMs = System.currentTimeMillis(),
                    tags = normalizeLikedTags(tags),
                )
                true
            } else {
                profileLikes -= postId
                false
            }
            likesFlow.value = likesFlow.value + (profile to profileLikes)
            persist()
            nowLiked
        }
    }

    override suspend fun clearLikes(profile: UserProfile) {
        mutex.withLock {
            likesFlow.value = likesFlow.value - profile
            persist()
        }
    }

    private fun persist() {
        val flattened = likesFlow.value
            .entries
            .flatMap { (profile, likes) ->
                likes.values.map { liked ->
                    LikedPostRecord.fromDomain(profile = profile, liked = liked)
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
    fun toDomain(): CodexItem {
        return CodexItem(
            codexId = codexId,
            postId = PostId(source = SourceKey.valueOf(source), sourcePostId = sourcePostId),
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
    val fullUrl: String?,
    val fullLocalPath: String?,
    val fullMime: String?,
    val pageUrl: String?,
    val width: Int?,
    val height: Int?,
    val canonicalTags: List<String>,
    val rawTags: List<String>,
    val authorName: String?,
    val createdAtEpochMs: Long?,
    val media: List<ImageRefRecord>? = null,
    val title: String? = null,
) {
    fun toDomain(): Post {
        return Post(
            id = PostId(
                source = SourceKey.valueOf(source),
                sourcePostId = sourcePostId,
            ),
            preview = ImageRef(
                url = previewUrl,
                localPath = previewLocalPath,
                mime = previewMime,
            ),
            full = if (fullUrl == null && fullLocalPath == null && fullMime == null) {
                null
            } else {
                ImageRef(
                    url = fullUrl,
                    localPath = fullLocalPath,
                    mime = fullMime,
                )
            },
            pageUrl = pageUrl,
            width = width,
            height = height,
            canonicalTags = canonicalTags,
            rawTags = rawTags,
            authorName = authorName,
            createdAtEpochMs = createdAtEpochMs,
            media = media.orEmpty().map { it.toDomain() },
            title = title,
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
                fullUrl = post.full?.url,
                fullLocalPath = post.full?.localPath,
                fullMime = post.full?.mime,
                pageUrl = post.pageUrl,
                width = post.width,
                height = post.height,
                canonicalTags = post.canonicalTags,
                rawTags = post.rawTags,
                authorName = post.authorName,
                createdAtEpochMs = post.createdAtEpochMs,
                media = post.media.map(ImageRefRecord::fromDomain),
                title = post.title,
            )
        }
    }
}

private data class ImageRefRecord(
    val url: String?,
    val localPath: String?,
    val mime: String?,
) {
    fun toDomain(): ImageRef {
        return ImageRef(
            url = url,
            localPath = localPath,
            mime = mime,
        )
    }

    companion object {
        fun fromDomain(ref: ImageRef): ImageRefRecord {
            return ImageRefRecord(
                url = ref.url,
                localPath = ref.localPath,
                mime = ref.mime,
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
) {
    fun toDomain(): Query {
        val mode = when (modeType) {
            "unified" -> QueryMode.Unified
            "source" -> QueryMode.Source(SourceKey.valueOf(requireNotNull(modeSource)))
            else -> QueryMode.Unified
        }
        return Query(
            mode = mode,
            includeTags = includeTags,
            excludeTags = excludeTags,
            sort = SortMode.valueOf(sort),
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
            )
        }
    }
}

private data class SettingsStoreFile(
    val enabledSources: List<String> = SourceKey.entries.map { it.name },
    val sourceWeights: Map<String, Double> = SourceRuntimeSettings().sourceWeights.mapKeys { it.key.name },
    val cacheFullImageOnSave: Boolean = false,
    val scenarioPreset: String = ScenarioPreset.NORMAL.name,
    val lastSelectedTabRoute: String = "search",
    val activeProfile: String? = null,
) {
    fun toDomain(): AppSettings {
        val runtime = SourceRuntimeSettings(
            enabledSources = enabledSources.mapNotNull { runCatching { SourceKey.valueOf(it) }.getOrNull() }.toSet(),
            sourceWeights = sourceWeights.mapNotNull { (key, value) ->
                runCatching { SourceKey.valueOf(key) }.getOrNull()?.let { source -> source to value }
            }.toMap(),
        )
        return normalizeSettings(
            AppSettings(
                runtime = runtime,
                cache = CacheSettings(cacheFullImageOnSave = cacheFullImageOnSave),
                scenarioPreset = runCatching { ScenarioPreset.valueOf(scenarioPreset) }.getOrDefault(ScenarioPreset.NORMAL),
                lastSelectedTabRoute = lastSelectedTabRoute,
                activeProfile = activeProfile
                    ?.let { value -> runCatching { UserProfile.valueOf(value) }.getOrNull() }
                    ?: UserProfile.USER_1,
            )
        )
    }

    companion object {
        fun fromDomain(settings: AppSettings): SettingsStoreFile {
            return SettingsStoreFile(
                enabledSources = settings.runtime.enabledSources.map { it.name },
                sourceWeights = settings.runtime.sourceWeights.mapKeys { it.key.name },
                cacheFullImageOnSave = settings.cache.cacheFullImageOnSave,
                scenarioPreset = settings.scenarioPreset.name,
                lastSelectedTabRoute = settings.lastSelectedTabRoute,
                activeProfile = settings.activeProfile.name,
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
    val profile: String? = null,
    val source: String? = null,
    val sourcePostId: String? = null,
    val likedAtEpochMs: Long? = null,
    val tags: List<String>? = null,
) {
    companion object {
        fun fromDomain(profile: UserProfile, liked: LikedPost): LikedPostRecord {
            return LikedPostRecord(
                profile = profile.name,
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
    file.parentFile?.mkdirs()
    file.writeText(json.toJson(payload))
}
