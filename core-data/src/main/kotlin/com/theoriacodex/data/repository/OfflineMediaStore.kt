package com.theoriacodex.data.repository

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.UUID
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Completed offline copies live outside disposable caches. The manifest is the commit point. */
class OfflineMediaStore(
    private val directory: File,
    private val gson: Gson = Gson(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val ownedPath = directory.absoluteFile.toPath().normalize()
    // Values retain only source refs, never the offline keys: removed copies live only as long as their Viewer refs.
    private val collectedOfflineRefs = ReferenceQueue<ImageRef>()
    private val sourceMediaByOfflineRef = mutableMapOf<OfflineRefKey, OfflineSourceMedia>()
    private val mutex = Mutex()
    private var revision = 0L
    private val ownerRevisions = mutableMapOf<String, Long>()
    private val activeStaging = java.util.concurrent.ConcurrentHashMap.newKeySet<File>()
    private val mutableSnapshot = MutableStateFlow(OfflineMediaSnapshot())
    val snapshot: StateFlow<OfflineMediaSnapshot> = mutableSnapshot

    /** Restore canonical source media before persistence or duration identity; no disk access is needed. */
    fun withoutOfflineLocations(post: Post): Post {
        val source = synchronized(sourceMediaByOfflineRef) {
            forgetCollectedOfflineRefs()
            (listOfNotNull(post.preview, post.full) + post.media).firstNotNullOfOrNull { sourceMediaByOfflineRef[OfflineRefKey(it)] }
        }
        val preview = if (isOfflineRef(post.preview)) source?.preview ?: stripOfflinePath(post.preview) else post.preview
        val full = if (post.full?.let(::isOfflineRef) == true && source != null) source.full else post.full?.let(::stripOfflinePath)
        val media = restoreSourceGallery(post.media, source)
        val mediaCount = if (source != null) source.mediaCount else post.mediaCount
        return if (preview == post.preview && full == post.full && media == post.media && mediaCount == post.mediaCount) post else
            post.copy(preview = preview, full = full, media = media, mediaCount = mediaCount)
    }

    private fun restoreSourceGallery(media: List<ImageRef>, source: OfflineSourceMedia?): List<ImageRef> = when {
        source == null -> media.map(::stripOfflinePath)
        media.isNotEmpty() && media.all(::isOfflineRef) -> source.media
        else -> media.mapIndexed { index, ref ->
            if (isOfflineRef(ref)) source.effectiveMedia.getOrNull(index) ?: stripOfflinePath(ref) else ref
        }
    }

    private fun isOfflineRef(ref: ImageRef): Boolean = ref.localPath?.let { path ->
        runCatching { File(path).absoluteFile.toPath().normalize().startsWith(ownedPath) }.getOrDefault(false)
    } == true

    private fun stripOfflinePath(ref: ImageRef): ImageRef = if (isOfflineRef(ref)) ref.copy(localPath = null) else ref

    private fun rememberSourceMedia(record: OfflineRecord) {
        val source = OfflineSourceMedia(record.sourcePost.preview, record.sourcePost.full, record.sourcePost.media, record.sourcePost.mediaCount)
        synchronized(sourceMediaByOfflineRef) {
            forgetCollectedOfflineRefs()
            (listOfNotNull(record.post.preview, record.post.full) + record.post.media)
                .filter(::isOfflineRef).forEach { sourceMediaByOfflineRef[OfflineRefKey(it, collectedOfflineRefs)] = source }
        }
    }

    private fun forgetCollectedOfflineRefs() {
        while (true) sourceMediaByOfflineRef.remove((collectedOfflineRefs.poll() as? OfflineRefKey) ?: return)
    }

    suspend fun refresh() = withContext(ioDispatcher) {
        mutex.withLock {
            directory.listFiles().orEmpty().filter { it.name.startsWith(".pending-") && it !in activeStaging }
                .forEach(File::deleteRecursively)
            publishSnapshot()
        }
    }

    suspend fun find(id: PostId): Post? = withContext(ioDispatcher) {
        mutex.withLock {
            readRecord(postDirectory(id))?.takeIf { it.post.id == id }?.also(::rememberSourceMedia)?.post
        }
    }

    /** Capture before queueing or resolving so a subsequent remove also invalidates waiting work. */
    suspend fun admit(owner: String): OfflineMediaAdmission = mutex.withLock {
        OfflineMediaAdmission(owner, revision to (ownerRevisions[owner] ?: 0L))
    }

    /** A complete existing copy can be shared by multiple collections without duplicate downloads. */
    suspend fun retain(id: PostId, owner: String, admission: OfflineMediaAdmission? = null): Post? = withContext(ioDispatcher) {
        mutex.withLock {
            admission?.let { validateAdmission(owner, it) }
            val folder = postDirectory(id)
            val record = readRecord(folder)?.takeIf { it.post.id == id } ?: return@withLock null
            writeManifest(folder, record.post, record.sourcePost, record.owners + owner)
            publishSnapshot()
            rememberSourceMedia(record)
            record.post
        }
    }

    /** Downloads are staged separately so failure or cancellation never replaces a completed copy. */
    suspend fun save(
        post: Post,
        media: List<ImageRef>,
        owner: String,
        admission: OfflineMediaAdmission? = null,
        acquire: suspend (ImageRef, File) -> Unit,
    ): Post = withContext(ioDispatcher) {
        require(owner.isNotBlank())
        require(media.isNotEmpty()) { "This post has no full media to save" }
        require(media.size >= (post.mediaCount ?: media.size)) { "The complete gallery has not loaded yet" }
        val admitted = admission ?: admit(owner)
        directory.mkdirs()
        val staged = File(directory, ".pending-${UUID.randomUUID()}").also { activeStaging.add(it); it.mkdirs() }
        try {
            val localMedia = media.mapIndexed { index, ref ->
                currentCoroutineContext().ensureActive()
                val output = File(staged, "$index.${extension(ref)}")
                acquire(ref, output)
                check(output.isFile && output.length() > 0L) { "No media bytes were saved" }
                ref.copy(localPath = output.name)
            }
            currentCoroutineContext().ensureActive()
            mutex.withLock { commit(post, localMedia, owner, admitted, staged) }
        } finally {
            staged.deleteRecursively()
            activeStaging.remove(staged)
        }
    }

    private fun commit(
        post: Post,
        localMedia: List<ImageRef>,
        owner: String,
        admission: OfflineMediaAdmission,
        staged: File,
    ): Post {
        validateAdmission(owner, admission)
        val folder = postDirectory(post.id).apply { mkdirs() }
        val old = readRecord(folder)
        if (old != null) {
            writeManifest(folder, old.post, old.sourcePost, old.owners + owner)
            publishSnapshot()
            rememberSourceMedia(old)
            return old.post
        }
        val generation = File(folder, UUID.randomUUID().toString())
        check(staged.renameTo(generation)) { "Could not finish saving offline media" }
        val completedMedia = localMedia.map { it.copy(localPath = File(generation, it.localPath!!).absolutePath) }
        val completed = post.copy(
            media = completedMedia,
            full = completedMedia.first(),
            mediaCount = completedMedia.size,
            // First-page still images are also useful offline card previews; video/ZIP retain their image preview.
            preview = completedMedia.first().takeIf { it.mime?.startsWith("image/") == true && it.mime != "image/ugoira" }
                ?: post.preview,
        )
        val sourcePost = withoutOfflineLocations(post)
        try {
            writeManifest(folder, completed, sourcePost, setOf(owner))
        } catch (failure: Exception) {
            generation.deleteRecursively()
            throw failure
        }
        folder.listFiles().orEmpty().filter { it.isDirectory && it != generation }.forEach(File::deleteRecursively)
        rememberSourceMedia(OfflineRecord(completed, sourcePost, setOf(owner)))
        publishSnapshot()
        return completed
    }

    suspend fun removeOwner(owner: String) = withContext(ioDispatcher) {
        mutex.withLock {
            ownerRevisions[owner] = (ownerRevisions[owner] ?: 0L) + 1L
            directory.listFiles().orEmpty().filter(File::isDirectory).forEach { folder ->
                val record = readRecord(folder) ?: return@forEach
                if (owner !in record.owners) return@forEach
                val retained = record.owners - owner
                if (retained.isEmpty()) folder.deleteRecursively() else writeManifest(folder, record.post, record.sourcePost, retained)
            }
            publishSnapshot()
        }
    }

    suspend fun clear() = withContext(ioDispatcher) {
        mutex.withLock {
            revision++
            directory.listFiles().orEmpty().filterNot { it.name.startsWith(".pending-") }.forEach(File::deleteRecursively)
            publishSnapshot()
        }
    }

    private fun validateAdmission(owner: String, admission: OfflineMediaAdmission) {
        check(admission.owner == owner && admission.revision == (revision to (ownerRevisions[owner] ?: 0L))) {
            "Offline download was removed"
        }
    }

    private fun readRecord(folder: File): OfflineRecord? = runCatching {
        val manifest = File(folder, "manifest.json")
        if (!manifest.isFile || manifest.length() !in 1..MAX_MANIFEST_BYTES) return null
        val root = JsonParser.parseString(manifest.readText()).asJsonObject
        if (root.get("version").asInt != 2) return null
        val post = gson.fromJson(root.get("post"), Post::class.java)
        val sourcePost = gson.fromJson(root.get("sourcePost"), Post::class.java)
        if (!hasCanonicalSourceSnapshot(post, sourcePost)) return null
        if (post.media.isEmpty()) return null
        val canonicalFolder = folder.canonicalFile
        if (post.media.any { ref ->
                val file = ref.localPath?.let(::File) ?: return@any true
                !file.canonicalFile.toPath().startsWith(canonicalFolder.toPath()) || !file.isFile || file.length() <= 0L
            }
        ) return null
        val expectedBytes = root.getAsJsonArray("mediaBytes").map { it.asLong }
        if (expectedBytes != post.media.map { File(it.localPath!!).length() }) return null
        OfflineRecord(post, sourcePost, root.getAsJsonArray("owners").map { it.asString }.toSet())
    }.getOrNull()

    private fun hasCanonicalSourceSnapshot(offline: Post, source: Post): Boolean {
        if (source.id != offline.id) return false
        return (listOfNotNull(source.preview, source.full) + source.media).none(::isOfflineRef)
    }

    private fun writeManifest(folder: File, post: Post, sourcePost: Post, owners: Set<String>) {
        val root = JsonObject().apply {
            addProperty("version", 2)
            add("post", gson.toJsonTree(post))
            add("sourcePost", gson.toJsonTree(sourcePost))
            add("owners", JsonArray().apply { owners.sorted().forEach(::add) })
            add("mediaBytes", JsonArray().apply { post.media.forEach { add(File(it.localPath!!).length()) } })
        }
        val temporary = File(folder, "manifest.pending")
        try {
            temporary.writeText(root.toString())
            if (temporary.length() > MAX_MANIFEST_BYTES) throw IOException("Offline gallery metadata is too large")
            Files.move(temporary.toPath(), File(folder, "manifest.json").toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }

    private fun publishSnapshot() {
        val records = directory.listFiles().orEmpty().filterNot { it.name.startsWith(".") }.mapNotNull(::readRecord)
        mutableSnapshot.value = OfflineMediaSnapshot(
            postCount = records.size,
            availablePostIds = records.mapTo(linkedSetOf()) { it.post.id },
            mediaCount = records.sumOf { it.post.media.size },
            bytes = directory.listFiles().orEmpty().filterNot { it.name.startsWith(".pending-") }
                .sumOf { folder -> folder.walkTopDown().filter(File::isFile).sumOf(File::length) },
            postsByOwner = buildMap {
                records.forEach { record -> record.owners.forEach { owner -> put(owner, (get(owner) ?: 0) + 1) } }
            },
        )
    }

    private fun postDirectory(id: PostId) = File(directory, digest("${id.source.name}:${id.sourcePostId}"))
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun extension(ref: ImageRef): String = when (ref.mime?.substringBefore(';')?.lowercase()) {
        "image/ugoira" -> "zip"
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "video/mp4" -> "mp4"
        "video/webm" -> "webm"
        else -> ref.url?.substringBefore('?')?.substringAfterLast('.', "")
            ?.takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) } ?: "bin"
    }

    /** Identity matters: equal refs decoded by a later read must not replace an existing Viewer's weak key. */
    private class OfflineRefKey(ref: ImageRef, queue: ReferenceQueue<ImageRef>? = null) : WeakReference<ImageRef>(ref, queue) {
        private val identityHash = System.identityHashCode(ref)
        override fun hashCode(): Int = identityHash
        override fun equals(other: Any?): Boolean = this === other ||
            other is OfflineRefKey && get()?.let { it === other.get() } == true
    }

    private data class OfflineRecord(val post: Post, val sourcePost: Post, val owners: Set<String>)
    private data class OfflineSourceMedia(
        val preview: ImageRef,
        val full: ImageRef?,
        val media: List<ImageRef>,
        val mediaCount: Int?,
    ) {
        val effectiveMedia: List<ImageRef> get() = media.ifEmpty { listOfNotNull(full) }
    }
    private companion object { const val MAX_MANIFEST_BYTES = 8L * 1024L * 1024L }
}

data class OfflineMediaSnapshot(
    val postCount: Int = 0,
    val mediaCount: Int = 0,
    val bytes: Long = 0L,
    val postsByOwner: Map<String, Int> = emptyMap(),
    val availablePostIds: Set<PostId> = emptySet(),
)

class OfflineMediaAdmission internal constructor(
    internal val owner: String,
    internal val revision: Pair<Long, Long>,
)
