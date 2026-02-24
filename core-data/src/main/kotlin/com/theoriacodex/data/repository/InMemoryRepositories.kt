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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class InMemoryCodexRepository : CodexRepository {
    private val mutex = Mutex()
    private val codices = MutableStateFlow<List<Codex>>(emptyList())
    private val itemsByCodex = MutableStateFlow<Map<String, List<CodexItem>>>(emptyMap())

    override fun observeCodices(): Flow<List<Codex>> {
        return codices
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

    override suspend fun addItem(codexId: String, post: Post) {
        mutex.withLock {
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
        settings.value = transform(settings.value)
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
