package com.theoriacodex.data.android.room

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.theoriacodex.data.repository.ProfileLibraryIds
import com.theoriacodex.data.repository.ProfileLibraryWriteGuard
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RoomProfileDeletionTest {
    @Test fun `a later collection deletion failure rolls back the entire profile and preserves shared Posts`() = runTest {
        val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),
            TheoriaRoomDatabase::class.java).allowMainThreadQueries().build()
        try {
            val guard = ProfileLibraryWriteGuard()
            val repository = RoomCodexLikesRepository(database, writeGuard = guard)
            val profile = "profile-remove"
            val first = repository.ensureCodex(ProfileLibraryIds.codex(profile, "one"), "One")
            val second = repository.ensureCodex(ProfileLibraryIds.codex(profile, "two"), "Two")
            val keep = repository.ensureCodex(ProfileLibraryIds.codex("profile-main", "keep"), "Keep")
            val shared = post("shared")
            repository.addItems(first.codexId, listOf(shared, post("orphan")))
            repository.addItems(second.codexId, listOf(post("second")))
            repository.addItems(keep.codexId, listOf(shared))
            repository.toggleLikeAndSyncSystemCodex(profile, ProfileLibraryIds.likes(profile), "Likes", shared, listOf("tag"))
            database.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_second BEFORE DELETE ON codices " +
                "WHEN OLD.codex_id = '${second.codexId}' BEGIN SELECT RAISE(ABORT, 'injected failure'); END")
            assertTrue(runCatching { repository.removeProfileLibrary(profile) }.isFailure)
            assertNotNull(repository.observeCodex(first.codexId).first())
            assertNotNull(repository.observeCodex(second.codexId).first())
            assertEquals(1, repository.observeLikedPostIds(profile).first().size)
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_second")
            repository.removeProfileLibrary(profile)
            assertNull(repository.observeCodex(first.codexId).first())
            assertNull(repository.observeCodex(second.codexId).first())
            assertTrue(repository.observeLikedPostIds(profile).first().isEmpty())
            assertEquals(listOf(shared.id), repository.observeCodexItems(keep.codexId).first().map { it.postId })
            assertNotNull(repository.getPost(shared.id))
            assertNull(repository.getPost(post("orphan").id))
            guard.retire(profile)
            assertTrue(runCatching { repository.ensureCodex(first.codexId, "Stale") }.isFailure)
            assertTrue(runCatching { repository.toggleLikeAndSyncSystemCodex(profile, ProfileLibraryIds.likes(profile),
                "Stale Likes", shared, emptyList()) }.isFailure)
        } finally { database.close() }
    }

    private fun post(id: String) = Post(PostId(SourceKey.PIXIV, id), ImageRef(null, null, null), null,
        pageUrl = null, width = null, height = null, canonicalTags = listOf("tag"), rawTags = emptyList(),
        authorName = null, createdAtEpochMs = null)
}
