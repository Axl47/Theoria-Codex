package com.theoriacodex.data.android.room

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.theoriacodex.data.storage.LegacyJsonRecoveryRegistry
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * `RoomRecentsLegacyImporter.destinationSummary` is hashed into the stored migration proof and compared on every
 * launch while the legacy file is unarchived, so its bytes must never change, even after records leave Gson.
 */
@RunWith(RobolectricTestRunner::class)
class RecentsDestinationGoldenTest {
    private lateinit var database: TheoriaRoomDatabase

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            TheoriaRoomDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun tearDown() {
        database.close()
    }

    // Pins: the exact SHA-256 of the destination summary for rows holding `& = < > '` and a null originQueryHash.
    // The rows are seeded from golden payload bytes, so the hash does not depend on any encoder.
    @Test fun `destination summary hash stays byte identical`() {
        seedRows()

        assertEquals(PINNED_DESTINATION_SHA256, destinationSha256())
    }

    // Pins: golden/recents-destination-summary.txt is the canonical text behind the hash above.
    @Test fun `golden summary text hashes to the pinned destination hash`() {
        assertEquals(PINNED_DESTINATION_SHA256, sha256Hex(golden("recents-destination-summary.txt")))
    }

    private fun seedRows() {
        val content = database.codexLikesDao()
        content.insertPost(PostEntity("PIXIV", "gold-1", golden("post-payload.json")))
        val recents = database.recentsDao()
        recents.upsertWatched(RecentWatchedEntity("PIXIV", "gold-1", "WATCHED", 100L, 1L, "SEARCH", null, 1))
        recents.upsertWatched(RecentWatchedEntity("PIXIV", "gold-1", "CODEX", 90L, 2L, "CODEX", "h&=<>'", 3))
        recents.upsertSearch(RecentSearchEntity("hash&=<>'", golden("recents-search-payload-1.json"), 200L, 1L))
        recents.upsertSearch(RecentSearchEntity("hash2", golden("recents-search-payload-2.json"), 150L, 2L))
    }

    /** destinationSummary is private, and its result type is private too, so both are read reflectively. */
    private fun destinationSha256(): String {
        val importer = RoomRecentsLegacyImporter(database, LegacyJsonRecoveryRegistry())
        val summary = RoomRecentsLegacyImporter::class.java.getDeclaredMethod("destinationSummary")
            .apply { isAccessible = true }.invoke(importer)!!
        return summary.javaClass.getDeclaredMethod("getSha256").apply { isAccessible = true }.invoke(summary) as String
    }

    private fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray()).joinToString("") { "%02x".format(it) }

    private fun golden(name: String): String =
        checkNotNull(javaClass.getResource("/golden/$name")) { "missing golden $name" }.readText()
}

private const val PINNED_DESTINATION_SHA256 = "dc0c861137855a9586b60cbcd695c3152b26f588b7d3cca73156fcc5ca4db125"
