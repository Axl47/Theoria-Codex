package com.theoriacodex.data.android.room

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.theoriacodex.data.repository.ForYouVerdict
import com.theoriacodex.data.repository.ForYouVerdictKind
import com.theoriacodex.data.repository.MAX_FRAME_ATTEMPTS
import com.theoriacodex.data.repository.PictureVision
import com.theoriacodex.data.repository.ProfileLibraryWriteGuard
import com.theoriacodex.data.repository.VisionFrames
import com.theoriacodex.data.repository.VisionProgress
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.vision.PredictedTag
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        verdicts.remove(verdict("a", "2", ForYouVerdictKind.SKIPPED, at = 1))
        verdicts.remove(verdict("a", "3", ForYouVerdictKind.SAVED, at = 4))
        assertEquals(listOf("2"), verdicts.verdicts("a").map { it.postId.sourcePostId })

        guard.retire("a")
        RoomCodexLikesRepository(database, writeGuard = guard).removeProfileLibrary("a")
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

    @Test fun `a hash written after the tagger keeps its vision, and vision written after a hash keeps the hash`() = runTest {
        val library = RoomCodexLikesRepository(database)
        val signatures = RoomVisualSignatureRepository(database)
        val codex = library.ensureCodex("saved", "Saved")
        library.addItems(codex.codexId, listOf(post("a"), post("b")))

        signatures.putVision(listOf(vision("a", "m1", tags = listOf(tag("sky", 0.9f)))))
        signatures.put(id("a"), 5L)
        signatures.put(id("b"), 6L)
        signatures.putVision(listOf(vision("b", "m1", tags = listOf(tag("sea", 0.5f)))))

        assertEquals(mapOf(id("a") to 5L, id("b") to 6L), signatures.observePictureHashes().first())
        assertEquals(mapOf(id("a") to listOf(tag("sky", 0.9f)), id("b") to listOf(tag("sea", 0.5f))), signatures.observePredictedTags("m1").first())
        assertTrue(signatures.libraryPostsWithoutSignature(limit = 10).isEmpty())
    }

    @Test fun `a post only the tagger saw still waits for its hash, and a post the tagger cannot read is not retried`() = runTest {
        val library = RoomCodexLikesRepository(database)
        val signatures = RoomVisualSignatureRepository(database)
        val codex = library.ensureCodex("saved", "Saved")
        library.addItems(codex.codexId, listOf(post("seen"), post("unreadable"), post("new")))

        signatures.putVision(listOf(vision("seen", "m1", tags = emptyList()), vision("unreadable", "m1", embedding = null, tags = null), vision("never-stored", "m1")))

        assertEquals(setOf(id("seen"), id("unreadable"), id("new")), signatures.libraryPostsWithoutSignature(limit = 10).map(Post::id).toSet())
        assertTrue(signatures.observePictureHashes().first().isEmpty())
        assertEquals(mapOf(id("seen") to emptyList<PredictedTag>()), signatures.observePredictedTags("m1").first())
        assertEquals(listOf(id("new")), signatures.libraryPostsWithoutVision("m1", limit = 10).map(Post::id))
        assertEquals(setOf(id("seen"), id("unreadable"), id("new")), signatures.libraryPostsWithoutVision("m2", limit = 10).map(Post::id).toSet())
        assertEquals(1, signatures.libraryPostsWithoutVision("m2", limit = 1).size)
        signatures.put(id("seen"), 1L)
        assertEquals(setOf(id("unreadable"), id("new")), signatures.libraryPostsWithoutSignature(limit = 10).map(Post::id).toSet())
    }

    @Test fun `a still analysis awaiting frames is listed until its frames are read or its reads run out`() = runTest {
        val library = RoomCodexLikesRepository(database)
        val signatures = RoomVisualSignatureRepository(database)
        val codex = library.ensureCodex("saved", "Saved")
        library.addItems(codex.codexId, listOf(post("picture"), post("clip"), post("flaky"), post("gone")))
        signatures.putVision(
            listOf(vision("picture", "m1"), vision("clip", "m1", frames = VisionFrames.WANTED), vision("flaky", "m1", frames = VisionFrames.WANTED),
                vision("gone", "m1", frames = VisionFrames.WANTED)),
        )
        suspend fun awaiting() = signatures.libraryPostsAwaitingFrames("m1", limit = 10).map(Post::id).toSet()

        assertEquals(setOf(id("clip"), id("flaky"), id("gone")), awaiting())
        assertEquals(2, signatures.libraryPostsAwaitingFrames("m1", limit = 10, skip = 1).size)
        assertEquals(VisionProgress(analysed = 1, total = 4), signatures.observeVisionProgress("m1").first())
        assertTrue(signatures.libraryPostsAwaitingFrames("m2", limit = 10).isEmpty())
        signatures.putVision(listOf(vision("clip", "m1", frames = VisionFrames.READ)))
        signatures.failFrames(id("gone"), "m1", permanently = true)
        repeat(MAX_FRAME_ATTEMPTS - 1) { signatures.failFrames(id("flaky"), "m1", permanently = false) }
        assertEquals(setOf(id("flaky")), awaiting())
        assertEquals(3, signatures.observeVisionProgress("m1").first().analysed)
        signatures.failFrames(id("flaky"), "m1", permanently = false)
        assertTrue(awaiting().isEmpty())

        assertEquals(4, signatures.observeVisionProgress("m1").first().analysed)
        signatures.clearVision()
        assertTrue(awaiting().isEmpty())
    }

    @Test fun `a newer model replaces what an older one stored and progress counts each library post once`() = runTest {
        val library = RoomCodexLikesRepository(database)
        val signatures = RoomVisualSignatureRepository(database)
        val codex = library.ensureCodex("saved", "Saved")
        library.addItems(codex.codexId, listOf(post("a"), post("b")))
        library.toggleLikeAndSyncSystemCodex("p", "likes-p", "Likes", post("a"), emptyList())
        library.toggleLikeAndSyncSystemCodex("p", "likes-p", "Likes", post("c"), emptyList())
        signatures.putVision(listOf(vision("a", "m1", tags = listOf(tag("x", 0.4f))), vision("c", "m1", embedding = null, tags = null)))

        assertEquals(VisionProgress(analysed = 2, total = 3), signatures.observeVisionProgress("m1").first())
        assertEquals(VisionProgress(analysed = 0, total = 3), signatures.observeVisionProgress("m2").first())
        signatures.putVision(listOf(vision("a", "m2", embedding = byteArrayOf(9), tags = listOf(tag("y", 0.6f)))))

        assertEquals(VisionProgress(analysed = 1, total = 3), signatures.observeVisionProgress("m2").first())
        assertTrue(signatures.observePredictedTags("m1").first().keys == setOf<PostId>())
        assertEquals(mapOf(id("a") to listOf(tag("y", 0.6f))), signatures.observePredictedTags("m2").first())
        assertArrayEquals(byteArrayOf(9), database.openHelper.readableDatabase.query("SELECT embedding FROM post_visual_signatures WHERE source_post_id = 'a'").use { it.moveToFirst(); it.getBlob(0) })
    }

    @Test fun `clearing vision keeps hashes and drops rows that held only vision`() = runTest {
        val library = RoomCodexLikesRepository(database)
        val signatures = RoomVisualSignatureRepository(database)
        val codex = library.ensureCodex("saved", "Saved")
        library.addItems(codex.codexId, listOf(post("hashed"), post("visionOnly")))
        signatures.put(id("hashed"), 3L)
        signatures.putVision(listOf(vision("hashed", "m1", tags = listOf(tag("x", 0.5f))), vision("visionOnly", "m1", tags = listOf(tag("x", 0.5f)))))

        signatures.clearVision()

        assertEquals(mapOf(id("hashed") to 3L), signatures.observePictureHashes().first())
        assertTrue(signatures.observePredictedTags("m1").first().isEmpty())
        assertEquals(VisionProgress(analysed = 0, total = 2), signatures.observeVisionProgress("m1").first())
        assertEquals(listOf(id("visionOnly")), signatures.libraryPostsWithoutSignature(limit = 10).map(Post::id))
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM post_visual_signatures").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
    }

    @Test fun `vision rows follow their post when it is deleted`() = runTest {
        val library = RoomCodexLikesRepository(database)
        val signatures = RoomVisualSignatureRepository(database)
        val codex = library.ensureCodex("saved", "Saved")
        library.addItems(codex.codexId, listOf(post("gone")))
        signatures.putVision(listOf(vision("gone", "m1", tags = listOf(tag("x", 0.5f)))))

        library.removeItems(codex.codexId, setOf(id("gone")))

        assertTrue(signatures.observePredictedTags("m1").first().isEmpty())
        assertNull(database.openHelper.readableDatabase.query("SELECT 1 FROM post_visual_signatures").use { if (it.moveToFirst()) 1 else null })
    }

    private fun tag(name: String, score: Float) = PredictedTag(name, score, PredictedTag.Kind.GENERAL)

    private fun vision(
        post: String,
        model: String,
        embedding: ByteArray? = byteArrayOf(1, 2),
        tags: List<PredictedTag>? = emptyList(),
        frames: VisionFrames = VisionFrames.NONE,
    ) = PictureVision(id(post), model, embedding, tags, frames)

    private fun verdict(profile: String, post: String, kind: ForYouVerdictKind, at: Long) =
        ForYouVerdict(profile, id(post), kind, at, "https://example.com/$post.jpg")

    private fun id(value: String) = PostId(SourceKey.PIXIV, value)

    private fun post(value: String) = Post(
        id = id(value), preview = ImageRef("https://example.com/$value.jpg", null, "image/jpeg"), full = null, pageUrl = null,
        width = null, height = null, canonicalTags = emptyList(), rawTags = emptyList(), authorName = null, createdAtEpochMs = null,
    )
}
