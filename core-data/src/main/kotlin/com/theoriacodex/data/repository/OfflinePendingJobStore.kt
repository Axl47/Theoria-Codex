package com.theoriacodex.data.repository

import com.theoriacodex.data.storage.AtomicJsonFileStore
import com.theoriacodex.data.storage.DurableJson
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Device-only intent; verified completed manifests remain the authority for acquired bytes. */
class OfflinePendingJobStore(private val file: File, private val store: AtomicJsonFileStore = AtomicJsonFileStore()) {
    /** An unreadable queue fails so its owner can offer a retry; it is never silently emptied. */
    suspend fun load(): Map<String, List<PostId>> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyMap()
        require(file.length() <= 8L * 1024 * 1024) { "Offline queue is too large" }
        val root = DurableJson.decodeFromString(OfflineQueueFile.serializer(), file.readText(Charsets.UTF_8))
        require(root.version == 1)
        require(root.owners.size <= 200 && root.owners.sumOf { it.posts.size } <= 20_000)
        root.owners.associate { row ->
            val owner = row.owner.also { require(it.isNotBlank() && it.length <= 600) }
            owner to row.posts.map { post ->
                PostId(SourceKey.parseOrThrow(post.source), post.id.also { require(it.isNotBlank() && it.length <= 500) })
            }.distinct()
        }
    }

    suspend fun save(requests: Map<String, List<PostId>>) {
        require(requests.size <= 200 && requests.values.sumOf(List<PostId>::size) <= 20_000)
        val root = OfflineQueueFile(1, requests.map { (owner, posts) ->
            OfflineQueueOwner(owner, posts.distinct().map { id -> OfflineQueuePost(id.source.id, id.sourcePostId) })
        })
        store.writeIntent(file, root, OfflineQueueFile.serializer(), maxBytes = 8 * 1024 * 1024)
    }
}

@Serializable
internal data class OfflineQueueFile(
    @SerialName("version")
    val version: Int,
    @SerialName("owners")
    val owners: List<OfflineQueueOwner>,
)

@Serializable
internal data class OfflineQueueOwner(
    @SerialName("owner")
    val owner: String,
    @SerialName("posts")
    val posts: List<OfflineQueuePost>,
)

@Serializable
internal data class OfflineQueuePost(
    @SerialName("source")
    val source: String,
    @SerialName("id")
    val id: String,
)
