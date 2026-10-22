package com.theoriacodex.data.android.room

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.theoriacodex.data.repository.CodexCleaningRequest
import com.theoriacodex.data.repository.ProfileLibraryIds
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomCodexCleaningTest {
    private lateinit var database: TheoriaRoomDatabase
    private lateinit var repository: RoomCodexLikesRepository
    private var now = 100L
    private val profile = "profile-main"

    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TheoriaRoomDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = RoomCodexLikesRepository(database, clock = { now++ })
    }

    @After fun close() = database.close()

    @Test fun `union removes each canonical match once and Undo preserves newer saves`() = runTest {
        listOf("target", "a", "b", "untouched").forEach { repository.ensureCodex(it, it) }
        val one = post("one")
        val two = post("two")
        val otherProvider = post("one", SourceKey.AIBOORU)
        val retained = post("retained")
        repository.addItems("target", listOf(one, two, otherProvider, retained))
        repository.addItems("a", listOf(one, two))
        repository.addItems("b", listOf(one))
        repository.addItems("untouched", listOf(one, retained))
        val before = repository.observeCodexItems("target").first()
        val sources = listOf("a", "b", "untouched").associateWith { repository.observeCodexItems(it).first() }
        val request = CodexCleaningRequest(profile, "target", setOf("a", "b"))

        val snapshot = repository.cleanCodex(request)

        assertEquals(setOf(one.id, two.id), snapshot.items.map { it.postId }.toSet())
        assertEquals(setOf(otherProvider.id, retained.id), ids("target"))
        sources.forEach { (id, items) -> assertEquals(items, repository.observeCodexItems(id).first()) }
        assertNotNull(repository.getPost(one.id))
        assertTrue(repository.cleanCodex(request).items.isEmpty())
        repository.addItem("target", one)
        val newer = repository.observeCodexItems("target").first().single { it.postId == one.id }
        repository.restoreCleanedCodex(snapshot)
        assertEquals(newer, repository.observeCodexItems("target").first().single { it.postId == one.id })
        assertEquals(before.single { it.postId == two.id }, repository.observeCodexItems("target").first().single { it.postId == two.id })
        assertEquals(before.map { it.postId }.toSet(), ids("target"))
    }

    @Test fun `cleaning Likes unlikes only matches and Undo retains Like timestamps tags and newer Like`() = runTest {
        repository.ensureCodex("source", "Source")
        val system = ProfileLibraryIds.likes(profile)
        val one = post("one")
        val two = post("two")
        val keep = post("keep")
        listOf(one, two, keep).forEach { repository.toggleLikeAndSyncSystemCodex(profile, system, "Likes", it, listOf(it.title!!)) }
        repository.addItems("source", listOf(one, two))
        val originalLikes = repository.observeLikes(profile).first()
        val originalItems = repository.observeCodexItems(system).first()
        val otherProfile = "other"
        repository.toggleLikeAndSyncSystemCodex(otherProfile, ProfileLibraryIds.likes(otherProfile), "Other Likes", one, listOf("other"))
        val otherLikes = repository.observeLikes(otherProfile).first()

        val snapshot = repository.cleanCodex(CodexCleaningRequest(profile, system, setOf("source")))

        assertEquals(setOf(keep.id), repository.observeLikedPostIds(profile).first())
        assertEquals(setOf(keep.id), ids(system))
        assertEquals(setOf(one.id, two.id), ids("source"))
        assertEquals(otherLikes, repository.observeLikes(otherProfile).first())
        repository.toggleLikeAndSyncSystemCodex(profile, system, "Likes", one, listOf("new tags"))
        val newerLike = repository.observeLikes(profile).first().single { it.postId == one.id }
        val newerItem = repository.observeCodexItems(system).first().single { it.postId == one.id }
        repository.restoreCleanedCodex(snapshot)
        assertEquals(newerLike, repository.observeLikes(profile).first().single { it.postId == one.id })
        assertEquals(newerItem, repository.observeCodexItems(system).first().single { it.postId == one.id })
        assertEquals(originalLikes.single { it.postId == two.id }, repository.observeLikes(profile).first().single { it.postId == two.id })
        assertEquals(originalItems.single { it.postId == two.id }, repository.observeCodexItems(system).first().single { it.postId == two.id })
    }

    @Test fun `Likes as comparison never unlikes and no-match is a no-op`() = runTest {
        val system = ProfileLibraryIds.likes(profile)
        val shared = post("shared")
        repository.toggleLikeAndSyncSystemCodex(profile, system, "Likes", shared, listOf("tag"))
        repository.ensureCodex("target", "Target")
        repository.addItems("target", listOf(shared, post("keep")))
        val before = repository.observeLikes(profile).first()
        repository.cleanCodex(CodexCleaningRequest(profile, "target", setOf(system)))
        assertEquals(setOf(post("keep").id), ids("target"))
        assertEquals(before, repository.observeLikes(profile).first())
        assertEquals(setOf(shared.id), ids(system))
    }

    @Test fun `invalid requests missing selections and deleted Undo target fail without changes`() = runTest {
        repository.ensureCodex("target", "Target")
        repository.ensureCodex("source", "Source")
        val shared = post("shared")
        repository.addItems("target", listOf(shared))
        repository.addItems("source", listOf(shared))
        assertTrue(runCatching { CodexCleaningRequest(profile, "target", setOf("target")) }.isFailure)
        assertTrue(runCatching { CodexCleaningRequest(profile, "target", emptySet()) }.isFailure)
        assertTrue(runCatching { CodexCleaningRequest(profile, "target", setOf(ProfileLibraryIds.codex("other", "source"))) }.isFailure)
        assertTrue(runCatching { repository.cleanCodex(CodexCleaningRequest(profile, "target", setOf("source", "missing"))) }.isFailure)
        assertEquals(setOf(shared.id), ids("target"))
        val snapshot = repository.cleanCodex(CodexCleaningRequest(profile, "target", setOf("source")))
        repository.deleteCodex("target")
        assertTrue(runCatching { repository.restoreCleanedCodex(snapshot) }.isFailure)
        assertEquals(null, repository.observeCodex("target").first())
        assertEquals(setOf(shared.id), ids("source"))
    }

    @Test fun `Like deletion failure rolls back target removals and Likes together`() = runTest {
        val system = ProfileLibraryIds.likes(profile)
        val shared = post("shared")
        repository.toggleLikeAndSyncSystemCodex(profile, system, "Likes", shared, listOf("tag"))
        repository.ensureCodex("source", "Source")
        repository.addItems("source", listOf(shared))
        val items = repository.observeCodexItems(system).first()
        val likes = repository.observeLikes(profile).first()
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_clean BEFORE DELETE ON liked_posts BEGIN SELECT RAISE(ABORT, 'test failure'); END",
        )
        assertTrue(runCatching { repository.cleanCodex(CodexCleaningRequest(profile, system, setOf("source"))) }.isFailure)
        assertEquals(items, repository.observeCodexItems(system).first())
        assertEquals(likes, repository.observeLikes(profile).first())
    }

    private suspend fun ids(codexId: String) = repository.observeCodexItems(codexId).first().map { it.postId }.toSet()

    private fun post(id: String, source: SourceKey = SourceKey.PIXIV) = Post(
        id = PostId(source, id), preview = ImageRef("https://example.com/$id.jpg", null, "image/jpeg"),
        full = null, pageUrl = "https://example.com/$id", width = 100, height = 100,
        canonicalTags = listOf("tag"), rawTags = listOf("tag"), authorName = null, createdAtEpochMs = 1, title = id,
    )
}
