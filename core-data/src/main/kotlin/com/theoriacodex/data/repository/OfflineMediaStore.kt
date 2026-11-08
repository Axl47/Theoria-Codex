package com.theoriacodex.data.repository

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.data.storage.PostStorageCodec
import com.theoriacodex.data.storage.PostStorageRecord
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.PostTaxonomyTerm
import com.theoriacodex.domain.model.SearchFacet
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
    private val catalog = OfflineMediaCatalog()
    private var catalogLoaded = false
    private val mutableSnapshot = MutableStateFlow(OfflineMediaSnapshot())
    val snapshot: StateFlow<OfflineMediaSnapshot> = mutableSnapshot

    fun hasOfflineLocations(post: Post): Boolean =
        isOfflineRef(post.preview) || post.full?.let(::isOfflineRef) == true || post.media.any(::isOfflineRef)

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

    /** Startup owners may both call load; only explicit refresh reconciles the whole disk again. */
    suspend fun load() = withContext(ioDispatcher) { mutex.withLock { ensureCatalogLoaded() } }

    suspend fun refresh() = withContext(ioDispatcher) { mutex.withLock { rebuildCatalog() } }

    suspend fun find(id: PostId): Post? = withContext(ioDispatcher) {
        mutex.withLock {
            ensureCatalogLoaded()
            val folder = postDirectory(id)
            val record = readRecord(folder)?.takeIf { it.post.id == id }
            if (record == null) updateCatalog(folder, null)
            record?.also(::rememberSourceMedia)?.post
        }
    }

    /** Capture before queueing or resolving so a subsequent remove also invalidates waiting work. */
    suspend fun admit(owner: String): OfflineMediaAdmission = mutex.withLock {
        OfflineMediaAdmission(owner, revision to (ownerRevisions[owner] ?: 0L))
    }

    /** A complete existing copy can be shared by multiple collections without duplicate downloads. */
    suspend fun retain(id: PostId, owner: String, admission: OfflineMediaAdmission? = null): Post? = withContext(ioDispatcher) {
        mutex.withLock {
            ensureCatalogLoaded()
            admission?.let { validateAdmission(owner, it) }
            val folder = postDirectory(id)
            val record = readRecord(folder)?.takeIf { it.post.id == id } ?: return@withLock null
            if (owner !in record.owners) {
                val retained = record.copy(owners = record.owners + owner)
                writeManifest(folder, retained.post, retained.sourcePost, retained.owners)
                updateCatalog(folder, retained)
            }
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
            val stagedMedia = media.mapIndexed { index, ref ->
                currentCoroutineContext().ensureActive()
                val output = File(staged, "$index.${extension(ref)}")
                acquire(ref, output)
                check(output.isFile && output.length() > 0L) { "No media bytes were saved" }
                ref to output.name
            }
            currentCoroutineContext().ensureActive()
            mutex.withLock { commit(post, stagedMedia, owner, admitted, staged) }
        } finally {
            staged.deleteRecursively()
            activeStaging.remove(staged)
        }
    }

    private fun commit(
        post: Post,
        stagedMedia: List<Pair<ImageRef, String>>,
        owner: String,
        admission: OfflineMediaAdmission,
        staged: File,
    ): Post {
        validateAdmission(owner, admission)
        ensureCatalogLoaded()
        val folder = postDirectory(post.id).apply { mkdirs() }
        val old = readRecord(folder)
        // An undecodable manifest must not make other collections forget their copy.
        val priorOwners = if (old == null) salvageOwners(folder) else emptySet()
        if (old != null) {
            val retained = old.copy(owners = old.owners + owner)
            writeManifest(folder, retained.post, retained.sourcePost, retained.owners)
            updateCatalog(folder, retained)
            rememberSourceMedia(old)
            return old.post
        }
        val generation = File(folder, UUID.randomUUID().toString())
        check(staged.renameTo(generation)) { "Could not finish saving offline media" }
        val completedMedia = stagedMedia.map { (ref, name) -> ref.copy(localPath = File(generation, name).absolutePath) }
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
            writeManifest(folder, completed, sourcePost, priorOwners + owner)
        } catch (failure: Exception) {
            generation.deleteRecursively()
            throw failure
        }
        folder.listFiles().orEmpty().filter { it.isDirectory && it != generation }.forEach(File::deleteRecursively)
        val record = OfflineRecord(completed, sourcePost, priorOwners + owner)
        rememberSourceMedia(record)
        updateCatalog(folder, record)
        return completed
    }

    suspend fun removeOwner(owner: String) = withContext(ioDispatcher) {
        mutex.withLock {
            ownerRevisions[owner] = (ownerRevisions[owner] ?: 0L) + 1L
            ensureCatalogLoaded()
            catalog.foldersOwnedBy(owner).forEach { folder ->
                val record = readRecord(folder)
                if (record == null) { updateCatalog(folder, null); return@forEach }
                val retained = record.owners - owner
                if (retained.isEmpty()) folder.deleteRecursively() else writeManifest(folder, record.post, record.sourcePost, retained)
                updateCatalog(folder, if (retained.isEmpty()) readRecord(folder) else record.copy(owners = retained))
            }
        }
    }

    suspend fun clear() = withContext(ioDispatcher) {
        mutex.withLock {
            revision++
            directory.listFiles().orEmpty().filterNot { it.name.startsWith(".pending-") }.forEach(File::deleteRecursively)
            rebuildCatalog()
        }
    }

    private fun validateAdmission(owner: String, admission: OfflineMediaAdmission) {
        check(admission.owner == owner && admission.revision == (revision to (ownerRevisions[owner] ?: 0L))) {
            "Offline download was removed"
        }
    }

    /** [upgradeLegacy] rewrites a readable v2 manifest as v3; a failed rewrite keeps serving the v2 record. */
    private fun readRecord(folder: File, upgradeLegacy: Boolean = false): OfflineRecord? = runCatching {
        val manifest = File(folder, MANIFEST_NAME)
        if (!manifest.isFile || manifest.length() !in 1..MAX_MANIFEST_BYTES) return null
        val root = JsonParser.parseString(manifest.readText()).asJsonObject
        val version = root.get("version").asInt
        val (post, sourcePost) = decodePosts(root, version) ?: return null
        // A v2 read of renamed fields can land on the wrong values; the folder name is derived from the id.
        if (folder.name != digest("${post.id.source.id}:${post.id.sourcePostId}")) return null
        if (!hasCanonicalSourceSnapshot(post, sourcePost) || !mediaMatchesManifest(folder, post, root)) return null
        val owners = root.getAsJsonArray("owners").map { it.asString }.toSet()
        if (upgradeLegacy && version == LEGACY_MANIFEST_VERSION) {
            try { writeManifest(folder, post, sourcePost, owners) } catch (_: IOException) { /* retried on the next catalog build */ }
        }
        OfflineRecord(post, sourcePost, owners)
    }.getOrNull()

    private fun decodePosts(root: JsonObject, version: Int): Pair<Post, Post>? {
        val (post, sourcePost) = when (version) {
            MANIFEST_VERSION -> decodeStored(root.get("post")) to decodeStored(root.get("sourcePost"))
            LEGACY_MANIFEST_VERSION -> gson.fromJson(root.get("post"), Post::class.java).withLegacyDefaults() to
                gson.fromJson(root.get("sourcePost"), Post::class.java).withLegacyDefaults()
            else -> return null
        }
        return if (post == null || sourcePost == null) null else post to sourcePost
    }

    private fun mediaMatchesManifest(folder: File, post: Post, root: JsonObject): Boolean {
        if (post.media.isEmpty()) return false
        val canonicalFolder = folder.canonicalFile
        val files = post.media.map { ref -> ref.localPath?.let(::File) ?: return false }
        if (files.any { file ->
                !file.canonicalFile.toPath().startsWith(canonicalFolder.toPath()) || !file.isFile || file.length() <= 0L
            }
        ) return false
        return root.getAsJsonArray("mediaBytes").map { it.asLong } == files.map { it.length() }
    }

    private fun decodeStored(element: JsonElement): Post? =
        gson.fromJson(element, PostStorageRecord::class.java)?.let(PostStorageCodec::decode)

    /** Root owners are plain strings that R8 never renames, so they survive even when the post cannot be decoded. */
    private fun salvageOwners(folder: File): Set<String> = runCatching {
        val manifest = File(folder, MANIFEST_NAME)
        if (!manifest.isFile || manifest.length() !in 1..MAX_MANIFEST_BYTES) return emptySet()
        JsonParser.parseString(manifest.readText()).asJsonObject.getAsJsonArray("owners")
            .mapNotNull { element -> element.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString }.toSet()
    }.getOrDefault(emptySet())

    /**
     * Gson builds v2 posts without running constructors, so keys absent from older manifests leave null in
     * non-null fields. Fill them with the constructors' own defaults before anything else touches the post.
     */
    @Suppress("USELESS_ELVIS", "SENSELESS_COMPARISON")
    private fun Post.withLegacyDefaults(): Post {
        fun ImageRef.fixed() = copy(progressiveUrls = progressiveUrls ?: emptyList(), videoVariants = videoVariants ?: emptyList())
        val tags = canonicalTags ?: emptyList()
        return copy(
            preview = preview.fixed(),
            full = full?.fixed(),
            media = (media ?: emptyList()).map { it.fixed() },
            canonicalTags = tags,
            rawTags = rawTags ?: emptyList(),
            taxonomy = (taxonomy ?: tags.map { PostTaxonomyTerm(value = it) })
                .map { term -> if (term.facet == null) term.copy(facet = SearchFacet.TAG) else term },
            creatorProfiles = creatorProfiles ?: listOfNotNull(creatorProfile),
        )
    }

    private fun hasCanonicalSourceSnapshot(offline: Post, source: Post): Boolean {
        if (source.id != offline.id) return false
        return (listOfNotNull(source.preview, source.full) + source.media).none(::isOfflineRef)
    }

    private fun writeManifest(folder: File, post: Post, sourcePost: Post, owners: Set<String>) {
        val root = JsonObject().apply {
            addProperty("version", MANIFEST_VERSION)
            add("post", gson.toJsonTree(PostStorageCodec.encode(post)))
            add("sourcePost", gson.toJsonTree(PostStorageCodec.encode(sourcePost)))
            add("owners", JsonArray().apply { owners.sorted().forEach(::add) })
            add("mediaBytes", JsonArray().apply { post.media.forEach { add(File(checkNotNull(it.localPath) { "Offline media is missing its local path" }).length()) } })
        }
        val temporary = File(folder, "manifest.pending")
        try {
            temporary.writeText(root.toString())
            if (temporary.length() > MAX_MANIFEST_BYTES) throw IOException("Offline gallery metadata is too large")
            Files.move(temporary.toPath(), File(folder, MANIFEST_NAME).toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }

    private fun ensureCatalogLoaded() { if (!catalogLoaded) rebuildCatalog() }

    private fun rebuildCatalog() {
        catalog.clear()
        directory.listFiles().orEmpty().forEach { folder ->
            if (folder.name.startsWith(".pending-")) {
                if (folder !in activeStaging) folder.deleteRecursively()
            } else indexFolder(folder, readRecord(folder, upgradeLegacy = true))
        }
        catalogLoaded = true
        mutableSnapshot.value = catalog.snapshot()
    }

    private fun updateCatalog(folder: File, record: OfflineRecord?) {
        indexFolder(folder, record)
        mutableSnapshot.value = catalog.snapshot()
    }

    private fun indexFolder(folder: File, record: OfflineRecord?) {
        catalog.update(folder, record?.post?.id, record?.owners.orEmpty(), record?.post?.media?.size ?: 0,
            folder.walkTopDown().filter(File::isFile).sumOf(File::length))
    }

    private fun postDirectory(id: PostId) = File(directory, digest("${id.source.id}:${id.sourcePostId}"))
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
    private companion object {
        const val MAX_MANIFEST_BYTES = 8L * 1024L * 1024L
        const val MANIFEST_NAME = "manifest.json"
        /** v2 serialized the domain Post reflectively, so release builds wrote R8-renamed keys. */
        const val LEGACY_MANIFEST_VERSION = 2
        /** v3 serializes the keep-ruled PostStorageRecord with stable keys. */
        const val MANIFEST_VERSION = 3
    }
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
