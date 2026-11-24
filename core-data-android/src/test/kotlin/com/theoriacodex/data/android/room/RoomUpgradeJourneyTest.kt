package com.theoriacodex.data.android.room

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomUpgradeJourneyTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), TheoriaRoomDatabase::class.java)

    @Test fun `oldest saved library survives the entire registered upgrade and two reopens (ADR 0012)`() = runTest {
        RoomUpgradeScenario.verify(ApplicationProvider.getApplicationContext<Context>(), helper, 10)
    }

    @Test fun `a library from the shipped schema keeps likes, collections, automatic tags and recents through every later write (ADR 0012)`() = runTest {
        RoomUpgradeScenario.verify(ApplicationProvider.getApplicationContext<Context>(), helper, 11)
    }

    @Test fun `a library older than schema 10 is refused and left intact (ADR 0012)`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "schema-9.db"
        context.deleteDatabase(name)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name).apply { parentFile?.mkdirs() }, null).use {
            it.execSQL("CREATE TABLE codices (codex_id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL)")
            it.execSQL("INSERT INTO codices VALUES ('saved', 'Saved')")
            it.version = 9
        }
        val database = TheoriaRoomDatabase.create(context, name)
        val failure = runCatching { openProfileLibrary(database) }.exceptionOrNull()
        database.close()
        assertTrue(failure.toString(), failure is IllegalStateException)
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use {
            assertEquals(9, it.version)
            it.rawQuery("SELECT name FROM codices", null).use { rows ->
                assertTrue(rows.moveToFirst())
                assertEquals("Saved", rows.getString(0))
            }
        }
        context.deleteDatabase(name)
    }

    @Test fun `a version 14 picture hash keeps its meaning when the tagger columns arrive (ADR 0012)`() = runTest {
        RoomUpgradeScenario.verifyPictureVisionUpgrade(ApplicationProvider.getApplicationContext<Context>(), helper)
    }

    @Test fun `a library from a newer schema is refused and left intact (ADR 0012)`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "schema-newer.db"
        context.deleteDatabase(name)
        helper.createDatabase(name, 23).use {
            it.execSQL("INSERT INTO codices(codex_id,name,created_at_epoch_ms,display_order) VALUES('saved','Saved',1,0)")
            it.version = 24
        }
        val database = TheoriaRoomDatabase.create(context, name)
        val failure = runCatching { openProfileLibrary(database) }.exceptionOrNull()
        database.close()
        assertTrue(failure.toString(), failure is IllegalStateException)
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use {
            assertEquals(24, it.version)
            it.rawQuery("SELECT name FROM codices", null).use { rows ->
                assertTrue(rows.moveToFirst())
                assertEquals("Saved", rows.getString(0))
            }
        }
        context.deleteDatabase(name)
    }

    @Test fun `version 13 adds a verdict log without a post and signatures that follow their post (ADR 0017)`() {
        val name = "verdicts-and-signatures.db"
        helper.createDatabase(name, 12).use {
            it.execSQL("INSERT INTO posts(source,source_post_id,payload_json) VALUES('PIXIV','1','{}')")
        }
        helper.runMigrationsAndValidate(name, 13, true, TheoriaRoomDatabase.MIGRATION_12_13).use { database ->
            database.execSQL(
                "INSERT INTO for_you_verdicts(profile_id,source,source_post_id,decision,decided_at_epoch_ms,preview_url) " +
                    "VALUES('default','PIXIV','2','SKIPPED',1,NULL)",
            )
            database.execSQL("INSERT INTO post_visual_signatures(source,source_post_id,phash,indexed_at_epoch_ms) VALUES('PIXIV','1',94,1)")
            database.execSQL("PRAGMA foreign_keys=ON")
            database.execSQL("DELETE FROM posts WHERE source='PIXIV' AND source_post_id='1'")
            database.query("SELECT COUNT(*) FROM post_visual_signatures").use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
            }
            database.query("SELECT decision FROM for_you_verdicts").use {
                assertTrue(it.moveToFirst())
                assertEquals("SKIPPED", it.getString(0))
            }
        }
    }

    @Test fun `version 14 removes spike rows from the seven named tables, keeps named Custom Sites and spares verdicts (ADR 0012)`() {
        val name = "spike-rows.db"
        val tables = listOf("codex_items", "liked_posts", "recent_watched", "media_durations", "codex_automatic_tags", "post_visual_signatures", "posts")
        helper.createDatabase(name, 13).use {
            it.execSQL("INSERT INTO codices(codex_id,name,created_at_epoch_ms,display_order) VALUES('saved','Saved',1,0)")
            for (source in listOf(SPIKE, NAMED_SITE, DIGIT_SITE)) {
                it.execSQL("INSERT INTO posts(source,source_post_id,payload_json) VALUES('$source','1','{}')")
                it.execSQL("INSERT INTO codex_items VALUES('saved','$source','1',1)")
                it.execSQL("INSERT INTO liked_posts VALUES('a','$source','1',1,'[]')")
                it.execSQL(
                    "INSERT INTO recent_watched(source,source_post_id,viewed_at_epoch_ms,sort_sequence,origin,section,max_viewed_media_number) " +
                        "VALUES('$source','1',1,1,'SEARCH','WATCHED',1)",
                )
                it.execSQL(
                    "INSERT INTO media_durations(source,source_post_id,media_fingerprint,decision,updated_at_epoch_ms) " +
                        "VALUES('$source','1','full-media-v1','KNOWN',1)",
                )
                it.execSQL("INSERT INTO codex_automatic_tags(codex_id,source,tag_key,tag_display,group_index) VALUES('saved','$source','sky','sky',0)")
                it.execSQL("INSERT INTO post_visual_signatures(source,source_post_id,phash,indexed_at_epoch_ms) VALUES('$source','1',94,1)")
                it.execSQL(
                    "INSERT INTO for_you_verdicts(profile_id,source,source_post_id,decision,decided_at_epoch_ms) VALUES('a','$source','1','SKIPPED',1)",
                )
            }
        }
        helper.runMigrationsAndValidate(name, 14, true, TheoriaRoomDatabase.MIGRATION_13_14).use { database ->
            for (table in tables + "for_you_verdicts") {
                database.query("SELECT source FROM `$table` ORDER BY source").use {
                    val sources = buildList { while (it.moveToNext()) add(it.getString(0)) }
                    val kept = listOfNotNull(SPIKE.takeIf { table == "for_you_verdicts" }, NAMED_SITE, DIGIT_SITE).sorted()
                    assertEquals(table, kept, sources)
                }
            }
        }
    }

    @Test fun `version 14 removes searches naming a spike source in any source field and keeps ones that only mention it (ADR 0012)`() {
        val name = "spike-searches.db"
        val searches = mapOf(
            "top" to """{"modeSource":"$SPIKE","includeTags":["sky"]}""",
            "nested" to """{"query":{"modeSource":"$SPIKE"},"sources":["PIXIV"]}""",
            "sources" to """{"query":{"modeSource":"PIXIV"},"sources":["PIXIV","$SPIKE"]}""",
            "source-tags" to """{"query":{"modeSource":"PIXIV"},"sourceTags":{"$SPIKE":["sky"]}}""",
            "tag-only" to """{"query":{"modeSource":"PIXIV","includeTags":["$SPIKE"]}}""",
            "named-site" to """{"query":{"modeSource":"$NAMED_SITE"},"sources":["$DIGIT_SITE"],"sourceTags":{"$DIGIT_SITE":[]}}""",
        )
        helper.createDatabase(name, 13).use {
            searches.entries.forEachIndexed { index, (hash, payload) ->
                it.execSQL("INSERT INTO recent_searches VALUES(?,?,?,?)", arrayOf<Any>(hash, payload, index, index))
            }
        }
        helper.runMigrationsAndValidate(name, 14, true, TheoriaRoomDatabase.MIGRATION_13_14).use { database ->
            database.query("SELECT query_hash FROM recent_searches ORDER BY query_hash").use {
                assertEquals(listOf("named-site", "tag-only"), buildList { while (it.moveToNext()) add(it.getString(0)) })
            }
        }
    }

    @Test fun `version 16 keeps picture hashes as tried and adds empty vision columns (ADR 0012)`() {
        val name = "vision-columns.db"
        helper.createDatabase(name, 15).use {
            it.execSQL("INSERT INTO posts(source,source_post_id,payload_json) VALUES('PIXIV','1','{}')")
            it.execSQL("INSERT INTO post_visual_signatures(source,source_post_id,phash,indexed_at_epoch_ms) VALUES('PIXIV','1',94,1)")
        }
        helper.runMigrationsAndValidate(name, 16, true, TheoriaRoomDatabase.MIGRATION_15_16).use { database ->
            database.query("SELECT phash, phash_checked, model_id, embedding, predicted_tags FROM post_visual_signatures").use {
                assertTrue(it.moveToFirst())
                assertEquals(94, it.getLong(0))
                assertEquals(1, it.getInt(1))
                for (column in 2..4) assertTrue(it.isNull(column))
            }
        }
    }

    @Test fun `version 17 marks every existing analysis as awaiting frames and nothing else (ADR 0012)`() {
        val name = "frame-state.db"
        helper.createDatabase(name, 16).use {
            for (id in listOf("1", "2")) it.execSQL("INSERT INTO posts(source,source_post_id,payload_json) VALUES('PIXIV','$id','{}')")
            it.execSQL(
                "INSERT INTO post_visual_signatures(source,source_post_id,phash,phash_checked,indexed_at_epoch_ms,model_id,predicted_tags) " +
                    "VALUES('PIXIV','1',94,1,1,'m1','')",
            )
            it.execSQL("INSERT INTO post_visual_signatures(source,source_post_id,phash,indexed_at_epoch_ms) VALUES('PIXIV','2',95,1)")
        }
        helper.runMigrationsAndValidate(name, 17, true, TheoriaRoomDatabase.MIGRATION_16_17).use { database ->
            database.query("SELECT source_post_id, frame_state, frame_attempts FROM post_visual_signatures ORDER BY source_post_id").use {
                val rows = buildList { while (it.moveToNext()) add(listOf(it.getString(0), it.getString(1), it.getString(2))) }
                assertEquals(listOf(listOf("1", "1", "0"), listOf("2", "0", "0")), rows)
            }
        }
    }

    @Test fun `a series stored before shared first pages keeps everything and does not share them (ADR 0012)`() {
        val name = "series-first-pages.db"
        helper.createDatabase(name, 17).use {
            it.execSQL(
                "INSERT INTO series (source, address, title, genres, language, followed, reading_mode, download_new) " +
                    "VALUES ('custom_mihon_7', '/a', 'A', '[]', 'en', 1, 'VERTICAL', 1)",
            )
        }
        helper.runMigrationsAndValidate(name, 18, true, TheoriaRoomDatabase.MIGRATION_17_18).use { database ->
            database.query("SELECT title, followed, reading_mode, download_new, chapters_share_first_page FROM series").use {
                assertTrue(it.moveToFirst())
                assertEquals(listOf("A", "1", "VERTICAL", "1", "0"), (0..4).map(it::getString))
            }
        }
    }

    @Test fun `a series stored before chapter downloads keeps everything and does not download new chapters`() {
        val name = "series-downloads.db"
        helper.createDatabase(name, 14).use {
            it.execSQL(
                "INSERT INTO series (source, address, title, genres, language, followed, reading_mode) " +
                    "VALUES ('custom_mihon_7', '/title/1', 'Title', '[]', 'en', 1, 'VERTICAL')",
            )
        }
        helper.runMigrationsAndValidate(name, 15, true, TheoriaRoomDatabase.MIGRATION_14_15).use { database ->
            database.query("SELECT title, followed, reading_mode, download_new FROM series").use {
                assertTrue(it.moveToFirst())
                assertEquals(listOf("Title", "1", "VERTICAL", "0"), (0..3).map(it::getString))
            }
        }
    }

    @Test fun `a series followed before follow times takes its first stored time, and nothing is read yet (ADR 0012)`() {
        val name = "series-following.db"
        helper.createDatabase(name, 18).use {
            it.execSQL("INSERT INTO series (source, address, title, genres, language, followed) VALUES ('custom_mihon_7', '/a', 'A', '', 'en', 1)")
            it.execSQL("INSERT INTO series (source, address, title, genres, language, followed) VALUES ('custom_mihon_7', '/b', 'B', '', 'en', 0)")
            for ((address, seen) in listOf("/a/1" to 30L, "/a/2" to 10L)) {
                it.execSQL(
                    "INSERT INTO series_entries (source, series_address, address, name, source_order, first_seen_at_epoch_ms, read, last_page) " +
                        "VALUES ('custom_mihon_7', '/a', '$address', 'Chapter', 0, $seen, 0, 0)",
                )
            }
        }
        helper.runMigrationsAndValidate(name, 19, true, TheoriaRoomDatabase.MIGRATION_18_19).use { database ->
            database.query("SELECT address, followed_at_epoch_ms FROM series ORDER BY address").use {
                val rows = buildList { while (it.moveToNext()) add(it.getString(0) to it.getString(1)) }
                assertEquals(listOf("/a" to "10", "/b" to null), rows)
            }
            database.query("SELECT COUNT(*) FROM series_entries WHERE last_read_at_epoch_ms IS NULL").use {
                assertTrue(it.moveToFirst())
                assertEquals(2, it.getInt(0))
            }
        }
    }

    @Test fun `a series and chapter stored before memos keep everything and have none`() {
        val name = "series-memos.db"
        helper.createDatabase(name, 19).use {
            it.execSQL("INSERT INTO series (source, address, title, genres, language, followed) VALUES ('custom_mihon_7', '/a', 'A', '', 'en', 1)")
            it.execSQL(
                "INSERT INTO series_entries (source, series_address, address, name, source_order, first_seen_at_epoch_ms, read, last_page) " +
                    "VALUES ('custom_mihon_7', '/a', '/a/1', 'Chapter', 0, 10, 1, 4)",
            )
        }
        helper.runMigrationsAndValidate(name, 20, true, TheoriaRoomDatabase.MIGRATION_19_20).use { database ->
            database.query("SELECT title, followed, memo FROM series").use {
                assertTrue(it.moveToFirst())
                assertEquals(listOf("A", "1", null), (0..2).map(it::getString))
            }
            database.query("SELECT read, last_page, memo FROM series_entries").use {
                assertTrue(it.moveToFirst())
                assertEquals(listOf("1", "4", null), (0..2).map(it::getString))
            }
        }
    }

    @Test fun `a series stored with a check time keeps everything else when the column goes (ADR 0012)`() {
        val name = "series-check-time.db"
        helper.createDatabase(name, 20).use {
            it.execSQL(
                "INSERT INTO series (source, address, title, genres, language, followed, last_opened_entry, last_checked_at_epoch_ms, memo) " +
                    "VALUES ('custom_mihon_7', '/a', 'A', '', 'en', 1, '/a/1', 50, '{}')",
            )
        }
        helper.runMigrationsAndValidate(name, 21, true, TheoriaRoomDatabase.MIGRATION_20_21).use { database ->
            database.query("SELECT title, followed, last_opened_entry, memo FROM series").use {
                assertTrue(it.moveToFirst())
                assertEquals(listOf("A", "1", "/a/1", "{}"), (0..3).map(it::getString))
            }
        }
    }

    @Test fun `the viewer translation cache goes and the library stays`() {
        val name = "translation-cache-drop.db"
        helper.createDatabase(name, 21).use {
            it.execSQL("INSERT INTO codices(codex_id,name,created_at_epoch_ms,display_order) VALUES('saved','Saved',1,0)")
            it.execSQL(
                "INSERT INTO viewer_translation_cache(backend_version,source_language,source_text_sha256,translated_text," +
                    "expires_at_epoch_ms,last_used_at_epoch_ms) VALUES('google-nmt-v1','JAPANESE','hash','English',100,1)",
            )
        }
        helper.runMigrationsAndValidate(name, 22, true, TheoriaRoomDatabase.MIGRATION_21_22).use { database ->
            database.query("SELECT name FROM codices").use {
                assertTrue(it.moveToFirst())
                assertEquals("Saved", it.getString(0))
            }
            database.query("SELECT name FROM sqlite_master WHERE name LIKE '%viewer_translation_cache%'").use {
                assertFalse(it.moveToFirst())
            }
        }
    }

    @Test fun `a stored series starts with no check time, so the next background run checks it (ADR 0015)`() {
        val name = "series-check-time-back.db"
        helper.createDatabase(name, 22).use {
            it.execSQL(
                "INSERT INTO series (source, address, title, genres, language, followed, followed_at_epoch_ms, memo) " +
                    "VALUES ('custom_mihon_7', '/a', 'A', '', 'en', 1, 40, '{}')",
            )
        }
        helper.runMigrationsAndValidate(name, 23, true, TheoriaRoomDatabase.MIGRATION_22_23).use { database ->
            database.query("SELECT title, followed, followed_at_epoch_ms, memo, checked_at_epoch_ms FROM series").use {
                assertTrue(it.moveToFirst())
                assertEquals(listOf("A", "1", "40", "{}", null), (0..4).map(it::getString))
            }
        }
    }

    private companion object {
        const val SPIKE = "custom_mihon_7"
        const val NAMED_SITE = "custom_mihon_example_org"
        const val DIGIT_SITE = "custom_mihon_9anime_org"
    }
}
