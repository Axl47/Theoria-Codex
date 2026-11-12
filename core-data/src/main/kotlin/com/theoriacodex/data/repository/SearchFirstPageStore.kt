package com.theoriacodex.data.repository

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.theoriacodex.data.storage.PostStorageCodec
import com.theoriacodex.data.storage.PostStorageRecord
import com.theoriacodex.domain.model.CommentHint
import com.theoriacodex.domain.model.Post
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The most recent search's first page, so reopening the app can paint it at once while the search refreshes. It holds
 * one search and is disposable. Unlike saved posts it keeps each post's comment hint, so restored cards still show
 * their comment badges before the refresh arrives: a missing, stale or unreadable file only costs that instant paint.
 */
class SearchFirstPageStore(
    private val file: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val gson = Gson()

    /** The saved posts for [executionKey], or nothing when the file holds another search. */
    suspend fun load(executionKey: String): List<Post> = withContext(ioDispatcher) {
        runCatching {
            if (!file.exists()) return@runCatching emptyList()
            val root = JsonParser.parseString(file.readText()).asJsonObject
            if (root.get(VERSION).asInt != CURRENT_VERSION || root.get(KEY).asString != executionKey) {
                return@runCatching emptyList()
            }
            root.getAsJsonArray(POSTS).mapNotNull { element ->
                PostStorageCodec.decode(gson.fromJson(element, PostStorageRecord::class.java))
                    ?.copy(commentHint = element.asJsonObject.get(COMMENT_HINT)?.asInt?.toCommentHint())
            }
        }.getOrDefault(emptyList())
    }

    suspend fun save(executionKey: String, posts: List<Post>) {
        withContext(ioDispatcher) {
            runCatching {
                val root = JsonObject().apply {
                    addProperty(VERSION, CURRENT_VERSION)
                    addProperty(KEY, executionKey)
                    add(
                        POSTS,
                        JsonArray().apply {
                            posts.take(MAX_POSTS).forEach { post ->
                                add(
                                    gson.toJsonTree(PostStorageCodec.encode(post)).asJsonObject.apply {
                                        post.commentHint?.let { addProperty(COMMENT_HINT, it.count ?: SOME_COMMENTS) }
                                    },
                                )
                            }
                        },
                    )
                }
                val temporary = File(file.parentFile, "${file.name}.tmp")
                temporary.writeText(root.toString())
                if (!temporary.renameTo(file)) temporary.delete()
            }
        }
    }

    private fun Int.toCommentHint() = CommentHint(takeIf { it != SOME_COMMENTS })

    companion object {
        /** Under the app's cache directory, so clearing disposable caches and low storage can reclaim it. */
        const val FILE_NAME = "search_first_page.json"
        private const val MAX_POSTS = 60
        private const val CURRENT_VERSION = 2
        private const val COMMENT_HINT = "commentHint"
        private const val SOME_COMMENTS = -1
        private const val VERSION = "version"
        private const val KEY = "key"
        private const val POSTS = "posts"
    }
}
