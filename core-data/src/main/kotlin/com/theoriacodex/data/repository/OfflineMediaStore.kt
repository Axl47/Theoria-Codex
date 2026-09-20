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
    private val mutex = Mutex()
    private var revision = 0L
    private val ownerRevisions = mutableMapOf<String, Long>()
    private val activeStaging = java.util.concurrent.ConcurrentHashMap.newKeySet<File>()
    private val mutableSnapshot = MutableStateFlow(OfflineMediaSnapshot())
    val snapshot: StateFlow<OfflineMediaSnapshot> = mutableSnapshot

    /** Offline locations are a runtime overlay, never durable shared post metadata. No disk access is needed. */
    fun withoutOfflineLocations(post: Post): Post {
        fun clean(ref: ImageRef): ImageRef {
            val owned = ref.localPath?.let { path ->
                runCatching { File(path).absoluteFile.toPath().normalize().startsWith(ownedPath) }.getOrDefault(false)
            } == true
            return if (owned) ref.copy(localPath = null) else ref
        }
        val preview = clean(post.preview)
        val full = post.full?.let(::clean)
        val media = post.media.map(::clean)
        return if (preview == post.preview && full == post.full && media == post.media) post else
            post.copy(preview = preview, full = full, media = media)
    }

    suspend fun refresh() = withContext(ioDispatcher) {
        mutex.withLock {
            directory.listFiles().orEmpty().filter { it.name.startsWith(".pending-") && it !in activeStaging }
                .forEach(File::deleteRecursively)
            publishSnapshot()
        }
    }

    suspend fun find(id: PostId): Post? = withContext(ioDispatcher) {
        mutex.withLock { readRecord(postDirectory(id))?.post?.takeIf { it.id == id } }
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
            writeManifest(folder, record.post, record.owners + owner)
            publishSnapshot()
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
            writeManifest(folder, old.post, old.owners + owner)
            publishSnapshot()
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
        try {
            writeManifest(folder, completed, setOf(owner))
        } catch (failure: Exception) {
            generation.deleteRecursively()
            throw failure
        }
        folder.listFiles().orEmpty().filter { it.isDirectory && it != generation }.forEach(File::deleteRecursively)
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
                if (retained.isEmpty()) folder.deleteRecursively() else writeManifest(folder, record.post, retained)
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
        if (root.get("version").asInt != 1) return null
        val post = gson.fromJson(root.get("post"), Post::class.java)
        if (post.media.isEmpty()) return null
        val canonicalFolder = folder.canonicalFile
        if (post.media.any { ref ->
                val file = ref.localPath?.let(::File) ?: return@any true
                !file.canonicalFile.toPath().startsWith(canonicalFolder.toPath()) || !file.isFile || file.length() <= 0L
            }
        ) return null
        val expectedBytes = root.getAsJsonArray("mediaBytes").map { it.asLong }
        if (expectedBytes != post.media.map { File(it.localPath!!).length() }) return null
        OfflineRecord(post, root.getAsJsonArray("owners").map { it.asString }.toSet())
    }.getOrNull()

    private fun writeManifest(folder: File, post: Post, owners: Set<String>) {
        val root = JsonObject().apply {
            addProperty("version", 1)
            add("post", gson.toJsonTree(post))
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

    private data class OfflineRecord(val post: Post, val owners: Set<String>)
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
