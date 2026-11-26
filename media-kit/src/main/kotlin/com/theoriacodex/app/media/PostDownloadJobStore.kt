package com.theoriacodex.app.media

import com.theoriacodex.app.viewer.EpisodeClipRange
import com.theoriacodex.data.storage.AtomicJsonFileStore
import com.theoriacodex.data.storage.DurableJson
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

interface PostDownloadJobStore {
    suspend fun load(): List<PostDownloadBatch>
    suspend fun save(batches: List<PostDownloadBatch>)
}

class FileBackedPostDownloadJobStore(private val file: File,
    private val store: AtomicJsonFileStore = AtomicJsonFileStore()) : PostDownloadJobStore {
    override suspend fun load(): List<PostDownloadBatch> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyList()
        require(file.length() <= 8L * 1024 * 1024) { "Download queue is too large" }
        val root = DurableJson.decodeFromString(DownloadQueueFile.serializer(), file.readText(Charsets.UTF_8))
        require(root.version in 1..3) { "Unsupported download queue version" }
        require(root.batches.size <= 200) { "Too many download batches" }
        require(root.batches.sumOf { it.items.size } <= 20_000) { "Too many queued downloads" }
        val batches = root.batches.map { batch ->
            PostDownloadBatch(batch.id, batch.title, batch.items.mapNotNull { row -> row.toItem(root.version) })
        }
        val ids = batches.flatMap { listOf(it.id) + it.items.map(PostDownloadItem::id) }
        require(ids.all { it > 0 } && ids.distinct().size == ids.size) { "Invalid download identities" }
        batches.filter { it.items.isNotEmpty() }
    }

    override suspend fun save(batches: List<PostDownloadBatch>) {
        require(batches.size <= 200 && batches.sumOf { it.items.size } <= 20_000) { "The download queue is full" }
        val root = DownloadQueueFile(3, batches.map { batch ->
            DownloadBatchRecord(batch.id, batch.title.take(200), batch.items.map(DownloadItemRecord::of))
        })
        store.writeIntent(file, root, DownloadQueueFile.serializer(), maxBytes = 8 * 1024 * 1024)
    }
}

@Serializable
internal data class DownloadQueueFile(
    @SerialName("version")
    val version: Int,
    @SerialName("batches")
    val batches: List<DownloadBatchRecord>,
)

@Serializable
internal data class DownloadBatchRecord(
    @SerialName("id")
    val id: Long,
    @SerialName("title")
    val title: String,
    @SerialName("items")
    val items: List<DownloadItemRecord>,
)

@Serializable
internal data class DownloadItemRecord(
    @SerialName("id")
    val id: Long,
    @SerialName("source")
    val source: String,
    @SerialName("postId")
    val postId: String,
    @SerialName("title")
    val title: String? = null,
    @SerialName("marker")
    val marker: String,
    @SerialName("origin")
    val origin: String? = null,
    @SerialName("untrackedPreparation")
    val untrackedPreparation: Boolean? = null,
    @SerialName("status")
    val status: String,
    @SerialName("downloadId")
    val downloadId: Long? = null,
    @SerialName("enqueuePending")
    val enqueuePending: Boolean? = null,
    @SerialName("page")
    val page: Int? = null,
    @SerialName("totalPages")
    val totalPages: Int? = null,
    @SerialName("clipStartMs")
    val clipStartMs: Long? = null,
    @SerialName("clipEndMs")
    val clipEndMs: Long? = null,
) {
    fun toItem(version: Int): PostDownloadItem? {
        val id = postId.also { require(it.isNotBlank() && it.length <= 500) }
        val post = Post(PostId(SourceKey.parseOrThrow(source), id), ImageRef(null, null, null), null,
            pageUrl = null, width = null, height = null, canonicalTags = emptyList(), rawTags = emptyList(),
            authorName = null, createdAtEpochMs = null, title = title)
        val selected = page?.let { page ->
            val total = requireNotNull(totalPages)
            require(page >= 0 && total > page)
            SelectedDownloadMedia(ImageRef(null, null, null), page, total)
        }
        val status = PostDownloadStatus.valueOf(status)
        val clip = clipStartMs?.let { start ->
            val range = clipEndMs?.let { end -> runCatching { EpisodeClipRange(start, end) }.getOrNull() } ?: return null
            EpisodeClipRequest(range, stream = null)
        }
        val restoredStatus = if (status in setOf(PostDownloadStatus.PREPARING, PostDownloadStatus.ENCODING,
                PostDownloadStatus.DOWNLOADING, PostDownloadStatus.WAITING)) PostDownloadStatus.QUEUED else status
        return PostDownloadItem(this.id,
            PostDownloadRequest(post, selected, marker, restored = true,
                untrackedPreparation = untrackedPreparation == true ||
                    version == 1 && status in setOf(PostDownloadStatus.PREPARING, PostDownloadStatus.FAILED),
                originLabel = origin?.take(200)?.let { if (it == "FYP") "For You" else it } ?: "Downloads", clip = clip),
            restoredStatus, downloadId, enqueuePending = enqueuePending ?: false)
    }

    companion object {
        fun of(item: PostDownloadItem) = DownloadItemRecord(
            id = item.id,
            source = item.request.post.id.source.id,
            postId = item.request.post.id.sourcePostId,
            title = item.request.post.title?.take(240),
            marker = item.request.marker,
            origin = item.request.originLabel.take(200),
            untrackedPreparation = true.takeIf { item.request.untrackedPreparation },
            status = item.status.name,
            downloadId = item.downloadId,
            enqueuePending = item.enqueuePending,
            page = item.request.selectedMedia?.pageIndex,
            totalPages = item.request.selectedMedia?.totalPages,
            clipStartMs = item.request.clip?.range?.startMs,
            clipEndMs = item.request.clip?.range?.endMs,
        )
    }
}
