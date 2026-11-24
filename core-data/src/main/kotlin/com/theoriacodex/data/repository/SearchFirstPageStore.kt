package com.theoriacodex.data.repository

import com.theoriacodex.data.storage.DurableJson
import com.theoriacodex.data.storage.PostStorageCodec
import com.theoriacodex.data.storage.PostStorageRecord
import com.theoriacodex.domain.model.CommentHint
import com.theoriacodex.domain.model.Post
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

class SearchFirstPageStore(
    private val file: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun load(executionKey: String): List<Post> = withContext(ioDispatcher) {
        runCatching {
            if (!file.exists()) return@runCatching emptyList()
            val root = DurableJson.parseToJsonElement(file.readText()).jsonObject
            if (root.getValue(VERSION).jsonPrimitive.int != CURRENT_VERSION || root.getValue(KEY).jsonPrimitive.content != executionKey) {
                return@runCatching emptyList()
            }
            root.getValue(POSTS).jsonArray.mapNotNull { element ->
                val stored = element.jsonObject
                PostStorageCodec.decode(DurableJson.decodeFromJsonElement(PostStorageRecord.serializer(), stored))
                    ?.copy(
                        commentHint = stored[COMMENT_HINT]?.jsonPrimitive?.int?.toCommentHint(),
                        pictureHash = stored[PICTURE_HASH]?.jsonPrimitive?.long,
                    )
            }
        }.getOrDefault(emptyList())
    }

    suspend fun save(executionKey: String, posts: List<Post>) {
        withContext(ioDispatcher) {
            runCatching {
                val root = buildJsonObject {
                    put(VERSION, CURRENT_VERSION)
                    put(KEY, executionKey)
                    putJsonArray(POSTS) {
                        posts.take(MAX_POSTS).forEach { post ->
                            val stored = DurableJson.encodeToJsonElement(PostStorageRecord.serializer(), PostStorageCodec.encode(post))
                                .jsonObject
                            val live = listOfNotNull(
                                post.commentHint?.let { COMMENT_HINT to JsonPrimitive(it.count ?: SOME_COMMENTS) },
                                post.pictureHash?.let { PICTURE_HASH to JsonPrimitive(it) },
                            )
                            add(JsonObject(stored + live))
                        }
                    }
                }
                val temporary = File(file.parentFile, "${file.name}.tmp")
                temporary.writeText(root.toString())
                if (!temporary.renameTo(file)) temporary.delete()
            }
        }
    }

    private fun Int.toCommentHint() = CommentHint(takeIf { it != SOME_COMMENTS })

    companion object {
        const val FILE_NAME = "search_first_page.json"
        private const val MAX_POSTS = 60
        private const val CURRENT_VERSION = 2
        private const val COMMENT_HINT = "commentHint"
        private const val PICTURE_HASH = "pictureHash"
        private const val SOME_COMMENTS = -1
        private const val VERSION = "version"
        private const val KEY = "key"
        private const val POSTS = "posts"
    }
}
