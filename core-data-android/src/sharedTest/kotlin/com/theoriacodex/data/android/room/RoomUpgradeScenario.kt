package com.theoriacodex.data.android.room

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import com.theoriacodex.data.repository.CodexSortMode
import com.theoriacodex.data.repository.RecentPostSection
import com.theoriacodex.data.repository.RecentSearchKind
import com.theoriacodex.data.repository.StoredMediaDurationKey
import com.theoriacodex.data.repository.StoredMediaDurationState
import com.theoriacodex.data.repository.ViewerOcrLanguage
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.data.repository.ViewerTranslationCacheKey
import com.theoriacodex.domain.model.CodexAutomaticTag
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/** Same persisted upgrade journey runs on Robolectric and Android SQLite through the production factory. */
object RoomUpgradeScenario {
    suspend fun verify(context: Context, helper: MigrationTestHelper, startingVersion: Int) = withContext<Unit>(Dispatchers.IO) {
        val name = "upgrade-journey-v$startingVersion.db"
        context.deleteDatabase(name)
        try {
            helper.createDatabase(name, startingVersion).use { seed(it, startingVersion) }
            val shared = post("shared")
            val durationKey = StoredMediaDurationKey(shared.id, "full-media-v1")
            val duration = StoredMediaDurationState.Known(12_000L, "PROVIDER")
            val translationKey = ViewerTranslationCacheKey("google-nmt-v1", ViewerOcrLanguage.JAPANESE, "青い空")

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
                assertLegacyRecents(recents, startingVersion)
                val expectedRules = if (startingVersion >= 4) {
                    listOf(CodexAutomaticTag(SourceKey.PIXIV, "cloud"), CodexAutomaticTag(SourceKey.PIXIV, "sky"))
                } else emptyList()
                assertEquals(expectedRules, collections.observeCodex("saved").first()?.automaticTags)

                collections.renameCodex("saved", "After upgrade")
                collections.setAutomaticTag("saved", CodexAutomaticTag(SourceKey.PIXIV, "blue", groupIndex = 1), true)
                recents.recordWatchedPost(shared, ViewerStreamSource.SEARCH, "search:sky", RecentPostSection.WATCHED)
                recents.recordWatchedPost(shared, ViewerStreamSource.CODEX, null, RecentPostSection.CODEX)
                recents.recordWatchedMediaProgress(shared, ViewerStreamSource.SEARCH, "search:sky", RecentPostSection.WATCHED, 3)
                recents.recordSearch(query(), "new-search", RecentSearchKind.SOURCE, listOf(SourceKey.PIXIV))
                RoomMediaDurationRepository(database).put(durationKey, duration)
                RoomViewerTranslationCacheRepository(database, clock = { 100L }).putAll(mapOf(translationKey to "Blue sky"))
                assertEquals(9, database.openHelper.readableDatabase.version)
                database.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
            }

            withDatabase(context, name) { database ->
                val collections = RoomCodexLikesRepository(database)
                val recents = RoomRecentsRepository(database)
                val saved = collections.observeCodex("saved").first()!!
                assertEquals("After upgrade", saved.name)
                assertEquals(
                    if (startingVersion >= 4) listOf(
                        CodexAutomaticTag(SourceKey.PIXIV, "cloud"), CodexAutomaticTag(SourceKey.PIXIV, "sky"),
                        CodexAutomaticTag(SourceKey.PIXIV, "blue", groupIndex = 1),
                    ) else listOf(CodexAutomaticTag(SourceKey.PIXIV, "blue")),
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
                val translations = RoomViewerTranslationCacheRepository(database, clock = { 101L })
                assertEquals(mapOf(translationKey to "Blue sky"), translations.getAll(setOf(translationKey)))
                assertTrue(translations.getAll(setOf(translationKey.copy(backendVersion = "google-nmt-v2"))).isEmpty())

                // Subsequent writes still honor membership isolation and actual foreign-key cascades.
                assertFalse(collections.toggleLikeAndSyncSystemCodex("a", "likes-a", "Likes A", shared, emptyList()).nowLiked)
                collections.deleteCodex("saved")
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

    private suspend fun assertLegacyRecents(recents: RoomRecentsRepository, version: Int) {
        val watched = recents.observeWatchedPosts().first()
        if (version < 2) {
            assertTrue(watched.isEmpty())
            assertTrue(recents.observeSearches().first().isEmpty())
            return
        }
        assertEquals(listOf("other", "shared"), watched.map { it.post.id.sourcePostId })
        assertEquals(listOf(RecentPostSection.CODEX, RecentPostSection.WATCHED), watched.map { it.section })
        assertEquals(listOf(ViewerStreamSource.CODEX, ViewerStreamSource.SEARCH), watched.map { it.origin })
        assertEquals(listOf(31L, 30L), watched.map { it.viewedAtEpochMs })
        assertEquals(if (version >= 6) 2 else 1, watched.last().maxViewedMediaNumber)
        val search = recents.observeSearches().first().single()
        assertEquals(query(), search.query)
        assertEquals(RecentSearchKind.SOURCE, search.kind)
        assertEquals(listOf(SourceKey.PIXIV), search.sources)
        assertEquals("old-search", search.queryHash)
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
        if (version >= 2) {
            val sectionColumn = if (version >= 3) ",section" else ""
            val progressColumn = if (version >= 6) ",max_viewed_media_number" else ""
            for ((index, id) in listOf("shared", "other").withIndex()) {
                val origin = if (index == 0) "SEARCH" else "CODEX"
                val sectionValue = if (version >= 3) ",'${if (index == 0) "WATCHED" else "CODEX"}'" else ""
                val progressValue = if (version >= 6) ",2" else ""
                database.execSQL("INSERT INTO recent_watched(source,source_post_id,viewed_at_epoch_ms,sort_sequence,origin,origin_query_hash$sectionColumn$progressColumn) " +
                    "VALUES('PIXIV',?,${30 + index},${index + 1},?,'search:sky'$sectionValue$progressValue)", arrayOf(id, origin))
            }
            database.execSQL("INSERT INTO recent_searches VALUES('old-search',?,32,1)", arrayOf(LEGACY_QUERY))
        }
        if (version >= 4) {
            for (tag in listOf("sky", "cloud")) {
                database.execSQL("INSERT INTO codex_automatic_tags(codex_id,source,tag_key,tag_display) VALUES('saved','PIXIV',?,?)", arrayOf(tag, tag))
            }
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

    private const val LEGACY_QUERY = """{"schemaVersion":1,"modeType":"source","modeSource":"PIXIV","includeTags":["sky"],"excludeTags":["comic"],"sort":"NEWEST"}"""
}
