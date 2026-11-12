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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class SearchFirstPageStoreTest {
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

    private fun file() = File(temporary.root, SearchFirstPageStore.FILE_NAME)

    @Test
    fun `a saved page comes back for its own search only`() = runTest {
        val store = SearchFirstPageStore(file(), StandardTestDispatcher(testScheduler))

        store.save("key-a", listOf(post("1"), post("2")))

        assertEquals(listOf("1", "2"), store.load("key-a").map { it.id.sourcePostId })
        assertTrue(store.load("key-b").isEmpty())
    }

    @Test
    fun `comment hints survive a restore, including a listing that gave none`() = runTest {
        val store = SearchFirstPageStore(file(), StandardTestDispatcher(testScheduler))
        val hints = listOf(CommentHint(3), CommentHint.Some, CommentHint.None, null)

        store.save("key", hints.mapIndexed { index, hint -> post("$index").copy(commentHint = hint) })

        assertEquals(hints, store.load("key").map { it.commentHint })
    }

    @Test
    fun `an unreadable file only costs the instant paint`() = runTest {
        file().writeText("{not json")

        assertTrue(SearchFirstPageStore(file(), StandardTestDispatcher(testScheduler)).load("key-a").isEmpty())
    }
}
