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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    private val thumbnails = CachedMediaDirectory(thumbnailDir)
    private val fullImages = CachedMediaDirectory(fullDir)
    private val namedThumbnailRevision = MutableStateFlow(0L)
    private val snapshotFlow = MutableStateFlow(CacheSnapshot(thumbnailCount = 0, fullImageCount = 0))

    init {
        snapshotFlow.value = runBlocking {
            withContext(ioDispatcher) {
                thumbnails.reconcile()
                fullImages.reconcile()
                currentSnapshot()
            }
        }
    }

    override fun observeNamedThumbnailRevisions(): Flow<Long> = namedThumbnailRevision.asStateFlow()

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
                    index = thumbnails,
                    key = key,
                    localPath = post.preview.localPath,
                    fallbackUrl = post.preview.url,
                    retainExistingBytes = replacePrefix == null,
                )
                if (replacePrefix != null) {
                    thumbnails.removeOtherKeys(replacePrefix, key)
                    namedThumbnailRevision.update { it + 1 }
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
                    index = fullImages,
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
                thumbnails.clear()
                namedThumbnailRevision.update { it + 1 }
                currentSnapshot()
            }
        }
    }

    override suspend fun clearFullImageCache() {
        mutex.withLock {
            snapshotFlow.value = withContext(ioDispatcher) {
                fullImages.clear()
                currentSnapshot()
            }
        }
    }

    private fun writeCachedEntry(
        index: CachedMediaDirectory,
        key: String,
        localPath: String?,
        fallbackUrl: String?,
        retainExistingBytes: Boolean = true,
    ) {
        val existing = index.files(key)
        val localFile = localPath?.let(::File)?.takeIf(::hasUsableBytes)
        val retained = if (localFile != null) {
            val extension = localFile.extension.takeIf { it.isNotBlank() } ?: "bin"
            index.directory.resolve("$key.$extension").also { output ->
                // Copy before removing old variants; copying an existing cache file to itself is a no-op.
                Files.copy(localFile.toPath(), output.toPath(), REPLACE_EXISTING)
            }
        } else {
            // Ordinary sparse saves retain bytes; a named cover may now represent a different post.
            existing.firstOrNull { file -> retainExistingBytes && file.extension != "url" && hasUsableBytes(file) }
                ?: index.directory.resolve("$key.url").also { it.writeText(fallbackUrl.orEmpty()) }
        }
        existing.filterNot { it == retained }.forEach { file -> file.delete(); index.update(file) }
        index.update(retained)
    }

    private fun hasUsableBytes(file: File): Boolean = file.isFile && file.canRead() && file.length() > 0

    private fun currentSnapshot() = CacheSnapshot(thumbnails.count, fullImages.count, thumbnails.bytes, fullImages.bytes)

    private fun cacheKey(postId: PostId): String = "${postId.source.name}_${postId.sourcePostId}"
}

/** One startup scan; writes reconcile only the affected files, including deletion failures. */
private class CachedMediaDirectory(val directory: File) {
    private val entries = mutableMapOf<String, MutableMap<File, Long>>()
    var count = 0
        private set
    var bytes = 0L
        private set

    fun files(key: String): List<File> = entries[key]?.keys?.toList().orEmpty()

    fun update(file: File) {
        val key = file.name.substringBeforeLast('.')
        entries[key]?.remove(file)?.let { old -> if (old > 0) { count--; bytes -= old } }
        if (file.isFile) {
            val size = if (file.extension != "url" && file.canRead()) file.length() else 0L
            entries.getOrPut(key) { mutableMapOf() }[file] = size
            if (size > 0) { count++; bytes += size }
        }
        if (entries[key]?.isEmpty() == true) entries.remove(key)
    }

    fun removeOtherKeys(prefix: String, retained: String) {
        entries.keys.filter { it.startsWith(prefix) && it != retained }.flatMap(::files).forEach { file ->
            file.delete()
            update(file)
        }
    }

    fun reconcile() {
        entries.clear()
        count = 0
        bytes = 0L
        directory.mkdirs()
        directory.listFiles().orEmpty().forEach(::update)
    }

    fun clear() {
        directory.deleteRecursively()
        reconcile()
    }
}
