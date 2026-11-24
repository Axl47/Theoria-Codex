package com.theoriacodex.data.android.room

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import com.theoriacodex.data.repository.CodexSortMode
import com.theoriacodex.data.repository.ForYouVerdict
import com.theoriacodex.data.repository.ForYouVerdictKind
import com.theoriacodex.data.repository.RecentPostSection
import com.theoriacodex.data.repository.RecentSearchKind
import com.theoriacodex.data.repository.StoredMediaDurationKey
import com.theoriacodex.data.repository.StoredMediaDurationState
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.CodexAutomaticTag
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/** Same persisted upgrade journey runs on Robolectric and Android SQLite through the production factory. */
object RoomUpgradeScenario {
    private const val PICTURE_HASH = 0x5E5E_0000_FFFFL
    private val seriesId = SeriesId(SourceKey.parseOrThrow("custom_mihon_7"), "/title/1")
    suspend fun verify(context: Context, helper: MigrationTestHelper, startingVersion: Int) = withContext<Unit>(Dispatchers.IO) {
        val name = "upgrade-journey-v$startingVersion.db"
        context.deleteDatabase(name)
        try {
            helper.createDatabase(name, startingVersion).use { seed(it, startingVersion) }
            val shared = post("shared")
            val durationKey = StoredMediaDurationKey(shared.id, "full-media-v1")
            val duration = StoredMediaDurationState.Known(12_000L, "PROVIDER")

            // Opening this factory, rather than passing individual migrations to the helper, proves
            // that an installed user's entire path is registered and Room accepts the final schema.
            withDatabase(context, name) { database ->
                val collections = RoomCodexLikesRepository(database, clock = { 100L })
                val recents = RoomRecentsRepository(database, clock = { 100L })
                assertEquals(listOf("saved", "likes-a", "likes-b"), collections.observeCodices().first().map { it.codexId })
                assertEquals(listOf(post("other"), shared), collections.observeCodexPosts("saved", CodexSortMode.NEWEST_SAVED).first())
                assertEquals(listOf(20L, 10L), collections.observeCodexItems("saved").first().map { it.savedAtEpochMs })
                for (profile in listOf("a", "b")) {
                    assertEquals(shared.id, collections.observeLikes(profile).first().single().postId)
                    assertEquals(listOf("sky"), collections.observeLikes(profile).first().single().tags)
                    assertEquals(listOf(shared), collections.observeCodexPosts("likes-$profile", CodexSortMode.NEWEST_SAVED).first())
                }
                assertLegacyRecents(recents)
                assertSpikeRowsDeleted(database)
                assertEquals(
                    listOf(CodexAutomaticTag(SourceKey.PIXIV, "cloud"), CodexAutomaticTag(SourceKey.PIXIV, "sky")),
                    collections.observeCodex("saved").first()?.automaticTags,
                )

                collections.renameCodex("saved", "After upgrade")
                collections.setAutomaticTag("saved", CodexAutomaticTag(SourceKey.PIXIV, "blue", groupIndex = 1), true)
                recents.recordWatchedPost(shared, ViewerStreamSource.SEARCH, "search:sky", RecentPostSection.WATCHED)
                recents.recordWatchedPost(shared, ViewerStreamSource.CODEX, null, RecentPostSection.CODEX)
                recents.recordWatchedMediaProgress(shared, ViewerStreamSource.SEARCH, "search:sky", RecentPostSection.WATCHED, 3)
                recents.recordSearch(query(), "new-search", RecentSearchKind.SOURCE, listOf(SourceKey.PIXIV))
                RoomMediaDurationRepository(database).put(durationKey, duration)
                RoomForYouVerdictRepository(database).record(ForYouVerdict("a", shared.id, ForYouVerdictKind.SKIPPED, 100L, null))
                RoomVisualSignatureRepository(database).put(shared.id, PICTURE_HASH)
                RoomSeriesRepository(database, clock = { 100L }).run {
                    store(Series(seriesId, "Title"), listOf(SeriesEntry("/c/2", "Ch.2"), SeriesEntry("/c/1", "Ch.1")))
                    setFollowed(seriesId, true)
                    recordProgress(seriesId, "/c/1", position = 4, length = 9)
                }
                assertEquals(23, database.openHelper.readableDatabase.version)
                database.openHelper.readableDatabase.query(
                    "SELECT name FROM sqlite_master WHERE name IN ('migration_metadata','recents_migration_metadata','viewer_translation_cache')",
                ).use { assertFalse(it.moveToFirst()) }
                database.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
            }

            withDatabase(context, name) { database ->
                val collections = RoomCodexLikesRepository(database)
                val recents = RoomRecentsRepository(database)
                val saved = collections.observeCodex("saved").first()!!
                assertEquals("After upgrade", saved.name)
                assertEquals(
                    listOf(
                        CodexAutomaticTag(SourceKey.PIXIV, "cloud"), CodexAutomaticTag(SourceKey.PIXIV, "sky"),
                        CodexAutomaticTag(SourceKey.PIXIV, "blue", groupIndex = 1),
                    ),
                    saved.automaticTags,
                )
                assertEquals(shared, collections.getPost(shared.id))
                val memberships = recents.observeWatchedPosts().first().filter { it.post.id == shared.id }
                assertEquals(setOf(RecentPostSection.WATCHED, RecentPostSection.CODEX), memberships.map { it.section }.toSet())
                assertEquals(3, memberships.single { it.section == RecentPostSection.WATCHED }.maxViewedMediaNumber)
                assertEquals(1, memberships.single { it.section == RecentPostSection.CODEX }.maxViewedMediaNumber)
                assertEquals(query(), recents.observeSearches().first().single { it.queryHash == "new-search" }.query)
                assertEquals(duration, RoomMediaDurationRepository(database).get(durationKey))
                assertNull(RoomMediaDurationRepository(database).get(durationKey.copy(mediaFingerprint = "full-media-v2")))
                assertEquals(listOf(ForYouVerdictKind.SKIPPED), RoomForYouVerdictRepository(database).verdicts("a").map { it.kind })
                assertEquals(mapOf(shared.id to PICTURE_HASH), RoomVisualSignatureRepository(database).observePictureHashes().first())
                val series = RoomSeriesRepository(database)
                assertEquals("/c/1" to true, series.series(seriesId)?.let { it.lastOpenedEntry to it.followed })
                assertEquals(4L to 9L, series.entries(seriesId).single { it.entry.address == "/c/1" }.let { it.position to it.length })

                // Subsequent writes still honor membership isolation and actual foreign-key cascades.
                assertFalse(collections.toggleLikeAndSyncSystemCodex("a", "likes-a", "Likes A", shared, emptyList()).nowLiked)
                collections.deleteCodexForUndo("saved")
            }

            withDatabase(context, name) { database ->
                val collections = RoomCodexLikesRepository(database)
                assertNull(collections.observeCodex("saved").first())
                assertTrue(collections.observeCodexItems("saved").first().isEmpty())
                assertTrue(database.codexLikesDao().automaticTagsForCodex("saved").isEmpty())
                assertTrue(collections.observeLikes("a").first().isEmpty())
                assertTrue(collections.observeCodexItems("likes-a").first().isEmpty())
                assertEquals(shared.id, collections.observeLikes("b").first().single().postId)
                assertEquals(listOf(shared), collections.observeCodexPosts("likes-b", CodexSortMode.NEWEST_SAVED).first())
                assertEquals(shared, collections.getPost(shared.id))
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    private suspend fun withDatabase(
        context: Context,
        name: String,
        block: suspend (TheoriaRoomDatabase) -> Unit,
    ) {
        val database = TheoriaRoomDatabase.create(context, name)
        try {
            block(database)
        } finally {
            database.close()
        }
    }

    /** The extension spike's posts are gone from every table, and the series tables that replace them open empty. */
    private fun assertSpikeRowsDeleted(database: TheoriaRoomDatabase) {
        val reader = database.openHelper.readableDatabase
        for (table in listOf("posts", "codex_items", "liked_posts", "recent_watched", "media_durations", "codex_automatic_tags")) {
            reader.query("SELECT COUNT(*) FROM `$table` WHERE source = 'custom_mihon_7'").use { rows ->
                rows.moveToFirst()
                assertEquals(table, 0, rows.getInt(0))
            }
        }
        reader.query("SELECT COUNT(*) FROM recent_searches WHERE query_hash = 'spike-search'").use { rows ->
            rows.moveToFirst()
            assertEquals(0, rows.getInt(0))
        }
        // Real custom sites' rows share the prefix and stay, as does a search for a tag that contains it.
        reader.query("SELECT COUNT(*) FROM posts WHERE source IN ('custom_mihonbooru_org', 'custom_mihon_example_org')").use { rows ->
            rows.moveToFirst()
            assertEquals(2, rows.getInt(0))
        }
        reader.query("SELECT COUNT(*) FROM recent_searches WHERE query_hash IN ('site-search', 'tag-search')").use { rows ->
            rows.moveToFirst()
            assertEquals(2, rows.getInt(0))
        }
        for (table in listOf("series", "series_entries")) {
            reader.query("SELECT COUNT(*) FROM `$table`").use { rows ->
                rows.moveToFirst()
                assertEquals(table, 0, rows.getInt(0))
            }
        }
    }

    private suspend fun assertLegacyRecents(recents: RoomRecentsRepository) {
        val watched = recents.observeWatchedPosts().first()
        assertEquals(listOf("other", "shared"), watched.map { it.post.id.sourcePostId })
        assertEquals(listOf(RecentPostSection.CODEX, RecentPostSection.WATCHED), watched.map { it.section })
        assertEquals(listOf(ViewerStreamSource.CODEX, ViewerStreamSource.SEARCH), watched.map { it.origin })
        assertEquals(listOf(31L, 30L), watched.map { it.viewedAtEpochMs })
        assertEquals(2, watched.last().maxViewedMediaNumber)
        val search = recents.observeSearches().first().single { it.queryHash == "old-search" }
        assertEquals(query(), search.query)
        assertEquals(RecentSearchKind.SOURCE, search.kind)
        assertEquals(listOf(SourceKey.PIXIV), search.sources)
    }

    private fun seed(database: SupportSQLiteDatabase, version: Int) {
        listOf("saved" to "Saved", "likes-a" to "Likes A", "likes-b" to "Likes B").forEachIndexed { index, (id, name) ->
            database.execSQL("INSERT INTO codices(codex_id,name,created_at_epoch_ms,display_order) VALUES(?,?,1,?)", arrayOf<Any>(id, name, index))
        }
        for (id in listOf("shared", "other")) {
            database.execSQL("INSERT INTO posts(source,source_post_id,payload_json) VALUES('PIXIV',?,?)", arrayOf(id, payload(id)))
        }
        database.execSQL("INSERT INTO codex_items VALUES('saved','PIXIV','shared',10)")
        database.execSQL("INSERT INTO codex_items VALUES('saved','PIXIV','other',20)")
        for (profile in listOf("a", "b")) {
            database.execSQL("INSERT INTO codex_items VALUES(?,'PIXIV','shared',15)", arrayOf("likes-$profile"))
            database.execSQL("INSERT INTO liked_posts VALUES(?,'PIXIV','shared',15,'[\"sky\"]')", arrayOf(profile))
        }
        // What the extension spike stored as posts; the v13 migration deletes it. A custom site with a similar id stays.
        database.execSQL("INSERT INTO posts(source,source_post_id,payload_json) VALUES('custom_mihon_7','L3M',?)", arrayOf(payload("spike")))
        database.execSQL("INSERT INTO posts(source,source_post_id,payload_json) VALUES('custom_mihonbooru_org','1',?)", arrayOf(payload("site")))
        database.execSQL("INSERT INTO posts(source,source_post_id,payload_json) VALUES('custom_mihon_example_org','1',?)", arrayOf(payload("named-site")))
        database.execSQL("INSERT INTO codex_items VALUES('saved','custom_mihon_7','L3M',5)")
        database.execSQL("INSERT INTO liked_posts VALUES('a','custom_mihon_7','L3M',5,'[]')")
        // Proof rows the retired importers wrote; the v11 migration drops their tables, so v1.9.2's schema 11 has neither.
        if (version < 11) database.execSQL("INSERT INTO migration_metadata VALUES('codex-likes','s','c','l','d','p',1,1,1,1,1,1,1,1,1)")
        for ((index, id) in listOf("shared", "other").withIndex()) {
            val origin = if (index == 0) "SEARCH" else "CODEX"
            val section = if (index == 0) "WATCHED" else "CODEX"
            database.execSQL("INSERT INTO recent_watched(source,source_post_id,viewed_at_epoch_ms,sort_sequence,origin,origin_query_hash,section,max_viewed_media_number) " +
                "VALUES('PIXIV',?,${30 + index},${index + 1},?,'search:sky',?,2)", arrayOf(id, origin, section))
        }
        database.execSQL("INSERT INTO recent_watched(source,source_post_id,viewed_at_epoch_ms,sort_sequence,origin,origin_query_hash,section,max_viewed_media_number) " +
            "VALUES('custom_mihon_7','L3M',29,9,'SEARCH','search:spike','WATCHED',1)")
        database.execSQL("INSERT INTO recent_searches VALUES('spike-search',?,31,3)", arrayOf(LEGACY_QUERY.replace("PIXIV", "custom_mihon_7")))
        database.execSQL("INSERT INTO recent_searches VALUES('site-search',?,34,4)", arrayOf(LEGACY_QUERY.replace("PIXIV", "custom_mihon_example_org")))
        database.execSQL("INSERT INTO recent_searches VALUES('tag-search',?,35,5)", arrayOf(LEGACY_QUERY.replace("sky", "custom_mihon_7")))
        database.execSQL("INSERT INTO recent_searches VALUES('old-search',?,32,1)", arrayOf(LEGACY_QUERY))
        // For You history; the v12 migration deletes it, so only the rows above survive.
        database.execSQL("INSERT INTO recent_searches VALUES('for_you:seed',?,33,2)", arrayOf(FOR_YOU_SEARCH))
        database.execSQL("INSERT INTO recent_watched(source,source_post_id,viewed_at_epoch_ms,sort_sequence,origin,origin_query_hash,section) " +
            "VALUES('PIXIV','other',34,3,'SEARCH','for_you:seed','FYP')")
        if (version < 11) database.execSQL("INSERT INTO recents_migration_metadata VALUES('recents','s',1,1,0,'d',1,1,'p',1,1)")
        for (tag in listOf("sky", "cloud")) {
            database.execSQL("INSERT INTO codex_automatic_tags(codex_id,source,tag_key,tag_display,group_index) VALUES('saved','PIXIV',?,?,0)", arrayOf(tag, tag))
        }
    }

    // Frozen storage-v1 payloads, independent of today's encoder, exercise genuine backward reads.
    private fun payload(id: String) = """
        {"schemaVersion":1,"source":"PIXIV","sourcePostId":"$id","previewUrl":"https://example.com/$id.jpg",
        "previewMime":"image/jpeg","previewProgressiveUrls":[],"previewIsAnimated":false,
        "fullUrl":"https://example.com/$id.png","fullMime":"image/png","fullProgressiveUrls":[],"fullIsAnimated":false,
        "width":800,"height":1200,"canonicalTags":["sky"],"rawTags":["sky"],"createdAtEpochMs":100,
        "media":[],"mediaCount":3,"title":"$id","taxonomy":[{"value":"sky","facet":"TAG"}],"creatorProfiles":[]}
    """.trimIndent()

    private fun post(id: String) = Post(
        id = PostId(SourceKey.PIXIV, id), preview = ImageRef("https://example.com/$id.jpg", null, "image/jpeg"),
        full = ImageRef("https://example.com/$id.png", null, "image/png"), pageUrl = null, width = 800, height = 1200,
        canonicalTags = listOf("sky"), rawTags = listOf("sky"), authorName = null, createdAtEpochMs = 100,
        mediaCount = 3, title = id,
    )

    private fun query() = Query(QueryMode.Source(SourceKey.PIXIV), includeTags = listOf("sky"),
        excludeTags = listOf("comic"), sort = SortMode.NEWEST, dateRange = null, minScore = null)

    private const val FOR_YOU_SEARCH = """{"recentSearchSchemaVersion":1,"query":{"schemaVersion":1,"modeType":"unified",""" +
        """"includeTags":["sky"],"excludeTags":[],"sort":"TOP"},"kind":"FYP","sources":["PIXIV"],"sourceTags":{"PIXIV":["sky"]}}"""

    private const val LEGACY_QUERY = """{"schemaVersion":1,"modeType":"source","modeSource":"PIXIV","includeTags":["sky"],"excludeTags":["comic"],"sort":"NEWEST"}"""

    /**
     * A version 14 library with a picture hash opens at the latest version with the hash kept as tried, no vision, and so
     * nothing awaiting frames.
     */
    suspend fun verifyPictureVisionUpgrade(context: Context, helper: MigrationTestHelper) = withContext<Unit>(Dispatchers.IO) {
        val name = "upgrade-vision-v14.db"
        context.deleteDatabase(name)
        try {
            helper.createDatabase(name, 14).use { database ->
                database.execSQL("INSERT INTO posts(source,source_post_id,payload_json) VALUES('PIXIV','shared',?)", arrayOf(payload("shared")))
                database.execSQL("INSERT INTO post_visual_signatures(source,source_post_id,phash,indexed_at_epoch_ms) VALUES('PIXIV','shared',$PICTURE_HASH,1)")
            }
            withDatabase(context, name) { database ->
                val signatures = RoomVisualSignatureRepository(database)
                assertEquals(mapOf(PostId(SourceKey.PIXIV, "shared") to PICTURE_HASH), signatures.observePictureHashes().first())
                database.openHelper.readableDatabase.query(
                    "SELECT phash_checked, model_id, embedding, predicted_tags, frame_state FROM post_visual_signatures",
                ).use { rows ->
                    assertTrue(rows.moveToFirst())
                    assertEquals(1, rows.getInt(0))
                    for (column in 1..3) assertTrue(rows.isNull(column))
                    assertEquals(0, rows.getInt(4))
                }
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
