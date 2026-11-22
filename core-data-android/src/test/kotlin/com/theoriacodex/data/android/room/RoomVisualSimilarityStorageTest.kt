package com.theoriacodex.data.android.room

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.theoriacodex.data.repository.ForYouVerdict
import com.theoriacodex.data.repository.ForYouVerdictKind
import com.theoriacodex.data.repository.ProfileLibraryWriteGuard
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomVisualSimilarityStorageTest {
    private val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TheoriaRoomDatabase::class.java)
        .allowMainThreadQueries().build()

    @After fun close() = database.close()

    @Test fun `a profile keeps its newest verdict per post up to the cap, and undo or removal forgets them`() = runTest {
        val guard = ProfileLibraryWriteGuard()
        val verdicts = RoomForYouVerdictRepository(database, guard, limit = 2)
        verdicts.record(verdict("a", "1", ForYouVerdictKind.SKIPPED, at = 1))
        verdicts.record(verdict("a", "1", ForYouVerdictKind.LIKED, at = 2))
        verdicts.record(verdict("a", "2", ForYouVerdictKind.HID, at = 3))
        verdicts.record(verdict("a", "3", ForYouVerdictKind.SAVED, at = 4))
        verdicts.record(verdict("b", "1", ForYouVerdictKind.SKIPPED, at = 5))

        assertEquals(listOf("3" to ForYouVerdictKind.SAVED, "2" to ForYouVerdictKind.HID), verdicts.verdicts("a").map { it.postId.sourcePostId to it.kind })
        verdicts.remove("a", id("3"))
        assertEquals(listOf("2"), verdicts.verdicts("a").map { it.postId.sourcePostId })

        guard.retire("a")
        RoomCodexLikesRepository(database, writeGuard = guard).removeProfileLibrary("a")
        // A swipe still on its way when the profile was deleted does not bring it back.
        assertTrue(runCatching { verdicts.record(verdict("a", "4", ForYouVerdictKind.LIKED, at = 6)) }.isFailure)
        assertTrue(verdicts.verdicts("a").isEmpty())
        assertEquals(listOf("1"), verdicts.verdicts("b").map { it.postId.sourcePostId })
    }

    @Test fun `signatures exist only for stored posts and list the library posts still missing one`() = runTest {
        val library = RoomCodexLikesRepository(database)
        val signatures = RoomVisualSignatureRepository(database)
        val codex = library.ensureCodex("saved", "Saved")
        library.addItems(codex.codexId, listOf(post("hashed"), post("flat"), post("waiting")))

        signatures.put(id("hashed"), 94L)
        signatures.put(id("flat"), null)
        signatures.put(id("never-stored"), 7L)

        assertEquals(mapOf(id("hashed") to 94L), signatures.observePictureHashes().first())
        assertEquals(listOf(id("waiting")), signatures.libraryPostsWithoutSignature(limit = 10).map(Post::id))
        library.removeItems(codex.codexId, setOf(id("hashed")))
        assertTrue(signatures.observePictureHashes().first().isEmpty())
    }

    private fun verdict(profile: String, post: String, kind: ForYouVerdictKind, at: Long) =
        ForYouVerdict(profile, id(post), kind, at, "https://example.com/$post.jpg")

    private fun id(value: String) = PostId(SourceKey.PIXIV, value)

    private fun post(value: String) = Post(
        id = id(value), preview = ImageRef("https://example.com/$value.jpg", null, "image/jpeg"), full = null, pageUrl = null,
        width = null, height = null, canonicalTags = emptyList(), rawTags = emptyList(), authorName = null, createdAtEpochMs = null,
    )
}
