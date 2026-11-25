package com.theoriacodex.data.android.room

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.theoriacodex.data.repository.ForYouVerdict
import com.theoriacodex.data.repository.ForYouVerdictKind
import com.theoriacodex.data.repository.PictureVision
import com.theoriacodex.data.repository.ProfileLibraryWriteGuard
import com.theoriacodex.data.repository.VisionCommit
import com.theoriacodex.data.repository.VisionFrames
import com.theoriacodex.data.repository.VisionProgress
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.vision.PredictedTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(VisionCommit::class)
@RunWith(RobolectricTestRunner::class)
class RoomVisualSimilarityStorageTest {
    private val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TheoriaRoomDatabase::class.java)
        .allowMainThreadQueries().build()

    @After fun close() = database.close()

    @Test fun `a profile keeps its newest verdict per post up to the cap, and undo or removal forgets them (ADR 0017)`() = runTest {
        val guard = ProfileLibraryWriteGuard()
        val verdicts = RoomForYouVerdictRepository(database, guard, limit = 2)
        verdicts.record(verdict("a", "1", ForYouVerdictKind.SKIPPED, at = 1))
        verdicts.record(verdict("a", "1", ForYouVerdictKind.LIKED, at = 2))
        assertEquals(listOf("1" to ForYouVerdictKind.LIKED), verdicts.verdicts("a").map { it.postId.sourcePostId to it.kind })
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

    @Test fun `recent skips are read newest first, only the profile's skips between the bounds, up to the limit`() = runTest {
        val verdicts = RoomForYouVerdictRepository(database)
        verdicts.record(verdict("a", "old", ForYouVerdictKind.SKIPPED, at = 9))
        verdicts.record(verdict("a", "liked", ForYouVerdictKind.LIKED, at = 10))
        verdicts.record(verdict("a", "1", ForYouVerdictKind.SKIPPED, at = 11))
        verdicts.record(verdict("a", "2", ForYouVerdictKind.SKIPPED, at = 12))
        verdicts.record(verdict("a", "3", ForYouVerdictKind.SKIPPED, at = 13))
        verdicts.record(verdict("b", "4", ForYouVerdictKind.SKIPPED, at = 14))

        assertEquals(listOf(id("3"), id("2")), verdicts.skippedBetween("a", sinceEpochMs = 10, untilEpochMs = 13, limit = 2).toList())
        assertEquals(setOf(id("2"), id("1")), verdicts.skippedBetween("a", sinceEpochMs = 10, untilEpochMs = 12, limit = 10))
    }

    @Test fun `signatures exist only for stored posts and list the library posts still missing one (ADR 0017)`() = runTest {
        val library = RoomCodexLikesRepository(database)
        val signatures = RoomVisualSignatureRepository(database)
        val codex = library.ensureCodex("saved", "Saved")
        library.addItems(codex.codexId, listOf(post("hashed"), post("flat"), post("waiting")))
        library.toggleLikeAndSyncSystemCodex("p", "likes-p", "Likes", post("liked"), emptyList())
        database.openHelper.writableDatabase.execSQL("DELETE FROM codex_items WHERE source_post_id = 'liked'")

        signatures.putHashes(mapOf(id("hashed") to 94L))
        signatures.putHashes(mapOf(id("flat") to null))
        signatures.putHashes(mapOf(id("never-stored") to 7L))

        assertEquals(mapOf(id("hashed") to 94L), signatures.observePictureHashes().first())
        assertEquals(setOf(id("waiting"), id("liked")), signatures.libraryPostsWithoutSignature(limit = 10).map(Post::id).toSet())
        library.removeItems(codex.codexId, setOf(id("hashed")))
        assertTrue(signatures.observePictureHashes().first().isEmpty())
    }

    @Test fun `a hash written after the tagger keeps its vision, and vision written after a hash keeps the hash (ADR 0017)`() = runTest {
        val library = RoomCodexLikesRepository(database)
        val signatures = RoomVisualSignatureRepository(database)
        val codex = library.ensureCodex("saved", "Saved")
        library.addItems(codex.codexId, listOf(post("a"), post("b")))

        signatures.putVision(listOf(vision("a", "m1", tags = listOf(tag("sky", 0.9f)))))
        signatures.putHashes(mapOf(id("a") to 5L))
        signatures.putHashes(mapOf(id("b") to 6L))
        signatures.putVision(listOf(vision("b", "m1", tags = listOf(tag("sea", 0.5f)))))

        assertEquals(mapOf(id("a") to 5L, id("b") to 6L), signatures.observePictureHashes().first())
        assertEquals(mapOf(id("a") to listOf(tag("sky", 0.9f)), id("b") to listOf(tag("sea", 0.5f))), signatures.predictedTags("m1"))
        assertTrue(signatures.libraryPostsWithoutSignature(limit = 10).isEmpty())
    }

    @Test fun `a post only the tagger saw still waits for its hash, and a post the tagger cannot read is not retried (ADR 0017)`() = runTest {
        val library = RoomCodexLikesRepository(database)
        val signatures = RoomVisualSignatureRepository(database)
        val codex = library.ensureCodex("saved", "Saved")
        library.addItems(codex.codexId, listOf(post("seen"), post("unreadable"), post("new")))

        signatures.putVision(listOf(vision("seen", "m1", tags = emptyList()), vision("unreadable", "m1", embedding = null, tags = null), vision("never-stored", "m1")))

        assertEquals(setOf(id("seen"), id("unreadable"), id("new")), signatures.libraryPostsWithoutSignature(limit = 10).map(Post::id).toSet())
        assertTrue(signatures.observePictureHashes().first().isEmpty())
        assertEquals(mapOf(id("seen") to emptyList<PredictedTag>()), signatures.predictedTags("m1"))
        assertEquals(listOf(id("new")), signatures.libraryPostsWithoutVision("m1", limit = 10).map(Post::id))
        assertEquals(setOf(id("seen"), id("unreadable"), id("new")), signatures.libraryPostsWithoutVision("m2", limit = 10).map(Post::id).toSet())
        assertEquals(1, signatures.libraryPostsWithoutVision("m2", limit = 1).size)
        signatures.putHashes(mapOf(id("seen") to 1L))
        assertEquals(setOf(id("unreadable"), id("new")), signatures.libraryPostsWithoutSignature(limit = 10).map(Post::id).toSet())
    }

    @Test fun `a still analysis awaiting frames is listed until its frames are read or its reads run out (ADR 0017)`() = runTest {
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
        repeat(2) { signatures.failFrames(id("flaky"), "m1", permanently = false) }
        assertEquals(setOf(id("flaky")), awaiting())
        assertEquals(3, signatures.observeVisionProgress("m1").first().analysed)
        signatures.failFrames(id("flaky"), "m1", permanently = false)
        assertTrue(awaiting().isEmpty())

        assertEquals(4, signatures.observeVisionProgress("m1").first().analysed)
        signatures.clearVision()
        assertTrue(awaiting().isEmpty())
    }

    @Test fun `the library total follows saves and likes, and vision writes are announced (ADR 0094)`() = runTest {
        val library = RoomCodexLikesRepository(database)
        val signatures = RoomVisualSignatureRepository(database)
        val totals = Channel<Int>(Channel.UNLIMITED)
        val changes = Channel<Unit>(Channel.UNLIMITED)
        val watching = launch(Dispatchers.Default) {
            launch { signatures.observeLibraryTotal().collect(totals::send) }
            launch { signatures.observeVisionChanges().collect(changes::send) }
        }
        assertEquals(0, totals.receive())
        changes.receive()

        library.addItems(library.ensureCodex("saved", "Saved").codexId, listOf(post("a"), post("b")))
        assertEquals(2, totals.receiveAsFlow().first { it == 2 })
        library.toggleLikeAndSyncSystemCodex("p", "likes-p", "Likes", post("c"), emptyList())
        assertEquals(3, totals.receiveAsFlow().first { it == 3 })
        signatures.putVision(listOf(vision("a", "m1")))
        changes.receive()
        watching.cancel()
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
        assertTrue(signatures.predictedTags("m1").keys == setOf<PostId>())
        assertEquals(mapOf(id("a") to listOf(tag("y", 0.6f))), signatures.predictedTags("m2"))
        assertArrayEquals(byteArrayOf(9), database.openHelper.readableDatabase.query("SELECT embedding FROM post_visual_signatures WHERE source_post_id = 'a'").use { it.moveToFirst(); it.getBlob(0) })
    }

    @Test fun `clearing vision keeps hashes and drops rows that held only vision (ADR 0017)`() = runTest {
        val library = RoomCodexLikesRepository(database)
        val signatures = RoomVisualSignatureRepository(database)
        val codex = library.ensureCodex("saved", "Saved")
        library.addItems(codex.codexId, listOf(post("hashed"), post("visionOnly"), post("flat")))
        signatures.putHashes(mapOf(id("hashed") to 3L, id("flat") to null))
        signatures.putVision(
            listOf(vision("hashed", "m1", tags = listOf(tag("x", 0.5f))), vision("visionOnly", "m1", tags = listOf(tag("x", 0.5f))), vision("flat", "m1", embedding = null, tags = null)),
        )

        signatures.clearVision()

        assertEquals(mapOf(id("hashed") to 3L), signatures.observePictureHashes().first())
        assertTrue(signatures.predictedTags("m1").isEmpty())
        assertEquals(VisionProgress(analysed = 0, total = 3), signatures.observeVisionProgress("m1").first())
        assertEquals(listOf(id("visionOnly")), signatures.libraryPostsWithoutSignature(limit = 10).map(Post::id))
        database.openHelper.readableDatabase.query("SELECT source_post_id, model_id FROM post_visual_signatures ORDER BY source_post_id").use {
            assertEquals(listOf("flat" to null, "hashed" to null), buildList { while (it.moveToNext()) add(it.getString(0) to it.getString(1)) })
        }
    }

    @Test fun `vision rows follow their post when it is deleted (ADR 0017)`() = runTest {
        val library = RoomCodexLikesRepository(database)
        val signatures = RoomVisualSignatureRepository(database)
        val codex = library.ensureCodex("saved", "Saved")
        library.addItems(codex.codexId, listOf(post("gone")))
        signatures.putVision(listOf(vision("gone", "m1", tags = listOf(tag("x", 0.5f)))))

        library.removeItems(codex.codexId, setOf(id("gone")))

        assertTrue(signatures.predictedTags("m1").isEmpty())
        assertNull(database.openHelper.readableDatabase.query("SELECT 1 FROM post_visual_signatures").use { if (it.moveToFirst()) 1 else null })
    }

    @Test fun `analyses of posts that do not play stop waiting for frames and count as analysed (ADR 0017)`() = runTest {
        val library = RoomCodexLikesRepository(database)
        val signatures = RoomVisualSignatureRepository(database)
        library.addItems(library.ensureCodex("saved", "Saved").codexId, listOf("still", "still2", "clip", "older").map(::post))
        signatures.putVision(listOf("still", "still2", "clip").map { vision(it, "m1", frames = VisionFrames.WANTED) } + vision("older", "m2", frames = VisionFrames.WANTED))
        assertEquals(VisionProgress(analysed = 0, total = 4), signatures.observeVisionProgress("m1").first())

        signatures.unwantFrames(listOf(id("still"), id("still2"), id("older")), "m1")
        signatures.failFrames(id("older"), "m1", permanently = true)

        assertEquals(listOf(id("clip")), signatures.libraryPostsAwaitingFrames("m1", limit = 10).map(Post::id))
        assertEquals(VisionProgress(analysed = 2, total = 4), signatures.observeVisionProgress("m1").first())
        assertEquals(listOf(id("older")), signatures.libraryPostsAwaitingFrames("m2", limit = 10).map(Post::id))
    }

    @Test fun `frame states are stored as 0 for a still, 1 for frames to read and 2 for frames read (ADR 0017)`() = runTest {
        val library = RoomCodexLikesRepository(database)
        val signatures = RoomVisualSignatureRepository(database)
        library.addItems(library.ensureCodex("saved", "Saved").codexId, listOf("none", "wanted", "read", "unwanted").map(::post))
        signatures.putVision(
            listOf(vision("none", "m1"), vision("wanted", "m1", frames = VisionFrames.WANTED), vision("read", "m1", frames = VisionFrames.READ),
                vision("unwanted", "m1", frames = VisionFrames.WANTED)),
        )
        signatures.unwantFrames(listOf(id("unwanted")), "m1")

        database.openHelper.readableDatabase.query("SELECT source_post_id, frame_state FROM post_visual_signatures ORDER BY source_post_id").use {
            val stored = buildMap { while (it.moveToNext()) put(it.getString(0), it.getInt(1)) }
            assertEquals(mapOf("none" to 0, "read" to 2, "unwanted" to 0, "wanted" to 1), stored)
        }
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
