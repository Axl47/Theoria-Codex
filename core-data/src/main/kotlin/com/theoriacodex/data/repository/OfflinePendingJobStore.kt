package com.theoriacodex.data.repository

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.theoriacodex.data.storage.AtomicJsonFileStore
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Device-only intent; verified completed manifests remain the authority for acquired bytes. */
class OfflinePendingJobStore(private val file: File, private val store: AtomicJsonFileStore = AtomicJsonFileStore()) {
    suspend fun load(): Map<String, List<PostId>> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyMap()
        require(file.length() <= 8L * 1024 * 1024) { "Offline queue is too large" }
        val root = JsonParser.parseString(file.readText(Charsets.UTF_8)).asJsonObject
        require(root.get("version").asInt == 1)
        require(root.getAsJsonArray("owners").size() <= 200)
        var count = 0
        root.getAsJsonArray("owners").associate { value ->
            val row = value.asJsonObject
            val owner = row.get("owner").asString.also { require(it.isNotBlank() && it.length <= 600) }
            val ids = row.getAsJsonArray("posts").map { item ->
                require(++count <= 20_000)
                val post = item.asJsonObject
                PostId(SourceKey.valueOf(post.get("source").asString), post.get("id").asString.also {
                    require(it.isNotBlank() && it.length <= 500)
                })
            }.distinct()
            owner to ids
        }
    }

    suspend fun save(requests: Map<String, List<PostId>>) {
        require(requests.size <= 200 && requests.values.sumOf(List<PostId>::size) <= 20_000)
        val root = JsonObject().apply {
            addProperty("version", 1)
            add("owners", JsonArray().apply { requests.forEach { (owner, posts) -> add(JsonObject().apply {
                addProperty("owner", owner)
                add("posts", JsonArray().apply { posts.distinct().forEach { id -> add(JsonObject().apply {
                    addProperty("source", id.source.name)
                    addProperty("id", id.sourcePostId)
                }) } })
            }) } })
        }
        store.writeIntent(file, root, maxBytes = 8 * 1024 * 1024)
    }
}
