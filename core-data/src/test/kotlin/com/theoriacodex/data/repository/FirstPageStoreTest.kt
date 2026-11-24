package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.CommentHint
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class FirstPageStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun post(id: String) = Post(
        id = PostId(SourceKey.DANBOORU, id),
        preview = ImageRef(url = "https://example.test/$id.jpg", localPath = null, mime = "image/jpeg"),
        full = null,
        pageUrl = null,
        width = 100,
        height = 200,
        canonicalTags = listOf("tag"),
        rawTags = listOf("tag"),
        authorName = null,
        createdAtEpochMs = null,
    )

    private fun file() = File(temporary.root, FirstPageStore.SEARCH_FILE_NAME)

    @Test
    fun `a saved page comes back for its own search only (ADR 0021)`() = runTest {
        val store = FirstPageStore(file(), StandardTestDispatcher(testScheduler))

        store.save("key-a", listOf(post("1"), post("2")))

        assertEquals(listOf("1", "2"), store.load("key-a").map { it.id.sourcePostId })
        assertTrue(store.load("key-b").isEmpty())
    }

    @Test
    fun `comment hints survive a restore, including a listing that gave none (ADR 0021)`() = runTest {
        val store = FirstPageStore(file(), StandardTestDispatcher(testScheduler))
        val hints = listOf(CommentHint(3), CommentHint.Some, CommentHint.None, null)

        store.save("key", hints.mapIndexed { index, hint -> post("$index").copy(commentHint = hint) })

        assertEquals(hints, store.load("key").map { it.commentHint })
    }

    @Test
    fun `file and picture hashes survive a restore (ADR 0021)`() = runTest {
        val store = FirstPageStore(file(), StandardTestDispatcher(testScheduler))
        val hashes = listOf("0123456789abcdef0123456789abcdef", null)
        val pictures = listOf(null, -0x7FF0_0000_0000_0001L)

        store.save("key", hashes.indices.map { index -> post("$index").copy(contentHash = hashes[index], pictureHash = pictures[index]) })

        assertEquals(hashes, store.load("key").map { it.contentHash })
        assertEquals(pictures, store.load("key").map { it.pictureHash })
    }

    @Test
    fun `clearing removes the page, and a refused write leaves nothing behind`() = runTest {
        val store = FirstPageStore(file(), StandardTestDispatcher(testScheduler))
        store.save("key", listOf(post("1")))

        store.clear()
        store.save("key", listOf(post("2"))) { false }

        assertTrue(store.load("key").isEmpty())
        assertTrue(!file().exists())
    }

    @Test
    fun `a page older than the store's age limit is not shown`() = runTest {
        var now = 0L
        val store = FirstPageStore(file(), StandardTestDispatcher(testScheduler), maxAgeMs = 1_000, now = { now })
        store.save("key", listOf(post("1")))
        now = file().lastModified() + 1_000

        assertEquals(listOf("1"), store.load("key").map { it.id.sourcePostId })
        now += 1
        assertTrue(store.load("key").isEmpty())
    }

    @Test
    fun `an unreadable file only costs the instant paint (ADR 0021)`() = runTest {
        file().writeText("{not json")

        assertTrue(FirstPageStore(file(), StandardTestDispatcher(testScheduler)).load("key-a").isEmpty())
    }

    @Test
    fun `one unreadable post leaves the rest of the cached page intact (ADR 0021)`() = runTest {
        val store = FirstPageStore(file(), StandardTestDispatcher(testScheduler))
        store.save("key", listOf(post("1"), post("2"), post("3")))
        val root = Json.parseToJsonElement(file().readText()).jsonObject
        val posts = root.getValue("posts").jsonArray
        val unreadable = JsonObject(posts[1].jsonObject + ("source" to JsonPrimitive("no_such_source")))
        file().writeText(JsonObject(root + ("posts" to JsonArray(listOf(posts[0], unreadable, posts[2])))).toString())

        assertEquals(listOf("1", "3"), store.load("key").map { it.id.sourcePostId })
    }
}
