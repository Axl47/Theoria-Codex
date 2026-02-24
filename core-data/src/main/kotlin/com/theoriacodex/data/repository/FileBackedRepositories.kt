package com.theoriacodex.data.repository

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.theoriacodex.domain.model.Codex
import com.theoriacodex.domain.model.CodexItem
import com.theoriacodex.domain.model.DateRange
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

    init {
        storageFile.parentFile?.mkdirs()
        val stored = readJson(storageFile, CodexStoreFile())
        codicesFlow.value = stored.codices.map { it.toDomain() }
        itemsFlow.value = stored.items.mapValues { entry -> entry.value.map { it.toDomain() } }
    }

    override fun observeCodices(): Flow<List<Codex>> {
        return codicesFlow
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

    override suspend fun addItem(codexId: String, post: Post) {
        mutex.withLock {
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
            settingsFlow.value = transform(settingsFlow.value)
            writeJson(storageFile, SettingsStoreFile.fromDomain(settingsFlow.value))
        }
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

private data class CodexStoreFile(
    val codices: List<CodexRecord> = emptyList(),
    val items: Map<String, List<CodexItemRecord>> = emptyMap(),
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
    val enabledSources: List<String>,
    val sourceWeights: Map<String, Double>,
    val cacheFullImageOnSave: Boolean,
    val lastSelectedTabRoute: String,
) {
    fun toDomain(): AppSettings {
        val runtime = SourceRuntimeSettings(
            enabledSources = enabledSources.map(SourceKey::valueOf).toSet(),
            sourceWeights = sourceWeights.mapKeys { SourceKey.valueOf(it.key) },
        )
        return AppSettings(
            runtime = runtime,
            cache = CacheSettings(cacheFullImageOnSave = cacheFullImageOnSave),
            lastSelectedTabRoute = lastSelectedTabRoute,
        )
    }

    companion object {
        fun fromDomain(settings: AppSettings): SettingsStoreFile {
            return SettingsStoreFile(
                enabledSources = settings.runtime.enabledSources.map { it.name },
                sourceWeights = settings.runtime.sourceWeights.mapKeys { it.key.name },
                cacheFullImageOnSave = settings.cache.cacheFullImageOnSave,
                lastSelectedTabRoute = settings.lastSelectedTabRoute,
            )
        }
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
