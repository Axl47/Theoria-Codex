package com.theoriacodex.data.android.room

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomSourceLibraryPresenceTest {
    private lateinit var database: TheoriaRoomDatabase
    private lateinit var repository: RoomCodexLikesRepository

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), TheoriaRoomDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = RoomCodexLikesRepository(database)
    }

    @After fun tearDown() = database.close()

    @Test fun `a source has library data once any of its posts is stored`() = runTest {
        assertFalse(repository.hasLibraryData(SourceKey.GELBOORU))

        val codex = repository.createCodex("Saved")
        repository.addItem(codex.codexId, post(SourceKey.GELBOORU))

        assertTrue(repository.hasLibraryData(SourceKey.GELBOORU))
        assertFalse(repository.hasLibraryData(SourceKey.DANBOORU))
    }

    private fun post(source: SourceKey) = Post(
        id = PostId(source, "1"), preview = ImageRef("https://example.com/1.jpg", null, "image/jpeg"), full = null,
        pageUrl = null, width = null, height = null, canonicalTags = emptyList(), rawTags = emptyList(),
        authorName = null, createdAtEpochMs = null,
    )
}
