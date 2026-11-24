package com.theoriacodex.data.repository

import com.theoriacodex.data.storage.DurableJson
import com.theoriacodex.data.storage.PostStorageCodec
import com.theoriacodex.data.storage.PostStorageRecord
import com.theoriacodex.domain.model.CommentHint
import com.theoriacodex.domain.model.Post
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

class FirstPageStore(
    private val file: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxAgeMs: Long? = null,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()

    suspend fun load(key: String): List<Post> = lock.withLock {
        withContext(ioDispatcher) { read(key) }
    }

    private fun read(key: String): List<Post> =
        runCatching {
            if (!file.exists() || maxAgeMs != null && now() - file.lastModified() > maxAgeMs) return@runCatching emptyList()
            val root = DurableJson.parseToJsonElement(file.readText()).jsonObject
            if (root.getValue(VERSION).jsonPrimitive.int != CURRENT_VERSION || root.getValue(KEY).jsonPrimitive.content != key) {
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

    suspend fun save(key: String, posts: List<Post>, admit: () -> Boolean = { true }) {
        lock.withLock {
            if (admit()) withContext(ioDispatcher) { write(key, posts) }
        }
    }

    suspend fun clear() {
        lock.withLock { withContext(ioDispatcher) { runCatching { temporary().delete(); file.delete() } } }
    }

    private fun write(key: String, posts: List<Post>) {
        runCatching {
            val root = buildJsonObject {
                put(VERSION, CURRENT_VERSION)
                put(KEY, key)
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
            val temporary = temporary()
            temporary.writeText(root.toString())
            if (!temporary.renameTo(file)) temporary.delete()
        }
    }

    private fun temporary() = File(file.parentFile, "${file.name}.tmp")

    private fun Int.toCommentHint() = CommentHint(takeIf { it != SOME_COMMENTS })

    companion object {
        const val SEARCH_FILE_NAME = "search_first_page.json"
        const val FOR_YOU_FILE_NAME = "for_you_deck.json"
        const val FOR_YOU_MAX_AGE_MS = 24L * 60 * 60 * 1000
        val FILE_NAMES = listOf(SEARCH_FILE_NAME, FOR_YOU_FILE_NAME)
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
