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
        cacheThumbnail(post, cacheKey(post.id))
    }

    override suspend fun cacheNamedThumbnail(name: String, post: Post) {
        cacheThumbnail(post, "${name}_${post.id.source.name}", replacePrefix = "${name}_")
    }

    private suspend fun cacheThumbnail(post: Post, key: String, replacePrefix: String? = null) {
        mutex.withLock {
            snapshotFlow.value = withContext(ioDispatcher) {
                writeCachedEntry(
                    targetDirectory = thumbnailDir,
                    key = key,
                    localPath = post.preview.localPath,
                    fallbackUrl = post.preview.url,
                )
                if (replacePrefix != null) {
                    thumbnailDir.listFiles().orEmpty()
                        .filter { it.isFile && it.name.startsWith(replacePrefix) && !it.name.startsWith("$key.") }
                        .forEach(File::delete)
                }
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
        val existing = targetDirectory.listFiles().orEmpty()
            .filter { file -> file.isFile && file.name.startsWith("$key.") }
        val localFile = localPath?.let(::File)?.takeIf(::hasUsableBytes)
        val retained = if (localFile != null) {
            val extension = localFile.extension.takeIf { it.isNotBlank() } ?: "bin"
            targetDirectory.resolve("$key.$extension").also { output ->
                // Copy before removing old variants; copying an existing cache file to itself is a no-op.
                Files.copy(localFile.toPath(), output.toPath(), REPLACE_EXISTING)
            }
        } else {
            // A sparse route snapshot must not replace downloaded bytes with a remote pointer.
            existing.firstOrNull { file -> file.extension != "url" && hasUsableBytes(file) }
                ?: targetDirectory.resolve("$key.url").also { it.writeText(fallbackUrl.orEmpty()) }
        }
        existing.filterNot { it == retained }.forEach(File::delete)
    }

    private fun hasUsableBytes(file: File): Boolean = file.isFile && file.canRead() && file.length() > 0

    private fun currentSnapshot(): CacheSnapshot {
        return CacheSnapshot(
            thumbnailCount = thumbnailDir.listFiles()?.count { it.isFile } ?: 0,
            fullImageCount = fullDir.listFiles()?.count { it.isFile } ?: 0,
        )
    }

    private fun cacheKey(postId: PostId): String = "${postId.source.name}_${postId.sourcePostId}"
}
