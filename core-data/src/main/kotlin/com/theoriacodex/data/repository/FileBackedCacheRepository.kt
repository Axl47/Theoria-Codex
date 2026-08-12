package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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

    override fun observeSnapshot(): Flow<CacheSnapshot> = snapshotFlow

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

        targetDirectory.resolve("$key.url").writeText(fallbackUrl.orEmpty())
    }

    private fun currentSnapshot(): CacheSnapshot {
        return CacheSnapshot(
            thumbnailCount = thumbnailDir.listFiles()?.count { it.isFile } ?: 0,
            fullImageCount = fullDir.listFiles()?.count { it.isFile } ?: 0,
        )
    }

    private fun cacheKey(postId: PostId): String = "${postId.source.name}_${postId.sourcePostId}"
}
