package com.theoriacodex.data.android.room

import android.content.Context
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.theoriacodex.data.repository.StoredMediaDurationKey
import com.theoriacodex.data.repository.StoredMediaDurationState
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomReadIsolationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: TheoriaRoomDatabase

    @Before fun setUp() {
        context.deleteDatabase(DATABASE_NAME)
        database = TheoriaRoomDatabase.create(context, DATABASE_NAME)
    }

    @After fun tearDown() {
        database.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test fun `single reads complete while an unrelated write transaction is open (ADR 0013)`(): Unit = runBlocking {
        val likes = RoomCodexLikesRepository(database)
        val durations = RoomMediaDurationRepository(database)
        val post = post("1")
        likes.addItem(likes.createCodex("Saved").codexId, post)
        val key = StoredMediaDurationKey(post.id, "fingerprint")
        val known = StoredMediaDurationState.Known(12_000L, "ACTIVE_PLAYER")
        durations.put(key, known)

        val writeOpen = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        val write = async(Dispatchers.IO) {
            database.withTransaction {
                database.codexLikesDao().insertPost(PostEntity("pixiv", "held", "{}"))
                writeOpen.complete(Unit)
                releaseWrite.await()
            }
        }
        try {
            writeOpen.await()
            withTimeout(READ_TIMEOUT_MS) {
                assertEquals(post.id, likes.getPost(post.id)?.id)
                assertTrue(likes.hasLibraryData(SourceKey.PIXIV))
                assertEquals(known, durations.get(key))
            }
        } finally {
            releaseWrite.complete(Unit)
            write.await()
        }
    }

    private fun post(id: String) = Post(
        id = PostId(SourceKey.PIXIV, id), preview = ImageRef("https://example.com/$id.jpg", null, "image/jpeg"),
        full = null, pageUrl = null, width = null, height = null, canonicalTags = emptyList(), rawTags = emptyList(),
        authorName = null, createdAtEpochMs = null,
    )

    private companion object {
        const val DATABASE_NAME = "read-isolation-test.db"
        const val READ_TIMEOUT_MS = 5_000L
    }
}
