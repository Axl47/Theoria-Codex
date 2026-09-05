package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
