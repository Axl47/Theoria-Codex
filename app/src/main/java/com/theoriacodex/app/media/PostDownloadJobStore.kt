package com.theoriacodex.app.media

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.theoriacodex.data.storage.AtomicJsonFileStore
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal interface PostDownloadJobStore {
    suspend fun load(): List<PostDownloadBatch>
    suspend fun save(batches: List<PostDownloadBatch>)
}

/** Operational identities only: no media URLs, credentials, paths or complete Post graphs. */
internal class FileBackedPostDownloadJobStore(private val file: File,
    private val store: AtomicJsonFileStore = AtomicJsonFileStore()) : PostDownloadJobStore {
    override suspend fun load(): List<PostDownloadBatch> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyList()
        require(file.length() <= 8L * 1024 * 1024) { "Download queue is too large" }
        val root = JsonParser.parseString(file.readText(Charsets.UTF_8)).asJsonObject
        val version = root.get("version").asInt
        require(version in 1..2) { "Unsupported download queue version" }
        val batches = root.getAsJsonArray("batches")
        require(batches.size() <= 200) { "Too many download batches" }
        var itemCount = 0
        batches.map { element ->
            val batch = element.asJsonObject
            val items = batch.getAsJsonArray("items").map { value ->
                require(++itemCount <= 20_000) { "Too many queued downloads" }
                val row = value.asJsonObject
                val source = SourceKey.valueOf(row.get("source").asString)
                val id = row.get("postId").asString.also { require(it.isNotBlank() && it.length <= 500) }
                val title = row.get("title")?.asString
                val post = Post(PostId(source, id), ImageRef(null, null, null), null,
                    pageUrl = null, width = null, height = null, canonicalTags = emptyList(), rawTags = emptyList(),
                    authorName = null, createdAtEpochMs = null, title = title)
                val selected = row.get("page")?.asInt?.let { page ->
                    val total = row.get("totalPages").asInt
                    require(page >= 0 && total > page)
                    SelectedDownloadMedia(ImageRef(null, null, null), page, total)
                }
                val status = PostDownloadStatus.valueOf(row.get("status").asString)
                val restoredStatus = if (status in setOf(PostDownloadStatus.PREPARING, PostDownloadStatus.DOWNLOADING,
                        PostDownloadStatus.WAITING)) PostDownloadStatus.QUEUED else status
                PostDownloadItem(row.get("id").asLong,
                    PostDownloadRequest(post, selected, row.get("marker").asString, restored = true,
                        untrackedPreparation = row.get("untrackedPreparation")?.asBoolean == true ||
                            version == 1 && status in setOf(PostDownloadStatus.PREPARING, PostDownloadStatus.FAILED)),
                    restoredStatus, row.get("downloadId")?.asLong, enqueuePending = row.get("enqueuePending")?.asBoolean ?: false)
            }
            PostDownloadBatch(batch.get("id").asLong, batch.get("title").asString, items)
        }.also { result ->
            val ids = result.flatMap { listOf(it.id) + it.items.map(PostDownloadItem::id) }
            require(ids.all { it > 0 } && ids.distinct().size == ids.size) { "Invalid download identities" }
        }
    }

    override suspend fun save(batches: List<PostDownloadBatch>) {
        require(batches.size <= 200 && batches.sumOf { it.items.size } <= 20_000) { "The download queue is full" }
        val root = JsonObject().apply {
            addProperty("version", 2)
            add("batches", JsonArray().apply { batches.forEach { batch -> add(JsonObject().apply {
                addProperty("id", batch.id)
                addProperty("title", batch.title.take(200))
                add("items", JsonArray().apply { batch.items.forEach { item -> add(JsonObject().apply {
                    addProperty("id", item.id)
                    addProperty("source", item.request.post.id.source.name)
                    addProperty("postId", item.request.post.id.sourcePostId)
                    item.request.post.title?.let { addProperty("title", it.take(240)) }
                    addProperty("marker", item.request.marker)
                    if (item.request.untrackedPreparation) addProperty("untrackedPreparation", true)
                    addProperty("status", item.status.name)
                    item.downloadId?.let { addProperty("downloadId", it) }
                    addProperty("enqueuePending", item.enqueuePending)
                    item.request.selectedMedia?.let { addProperty("page", it.pageIndex); addProperty("totalPages", it.totalPages) }
                }) } })
            }) } })
        }
        store.writeIntent(file, root, maxBytes = 8 * 1024 * 1024)
    }
}
