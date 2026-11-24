package com.theoriacodex.data.android.room

import android.content.Context
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

    @Test fun `oldest saved library survives the entire registered upgrade and two reopens`() = runTest {
        RoomUpgradeScenario.verify(ApplicationProvider.getApplicationContext<Context>(), helper, 10)
    }

    // Schema 11 is what v1.9.2, the last production release, shipped.
    @Test fun `a library from the shipped schema keeps likes, collections, automatic tags and recents through every later write`() = runTest {
        RoomUpgradeScenario.verify(ApplicationProvider.getApplicationContext<Context>(), helper, 11)
    }

    @Test fun `a version 14 picture hash keeps its meaning when the tagger columns arrive`() = runTest {
        RoomUpgradeScenario.verifyPictureVisionUpgrade(ApplicationProvider.getApplicationContext<Context>(), helper)
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

    @Test fun `a series followed before follow times takes its first stored time, and nothing is read yet`() {
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

    @Test fun `a series stored with a check time keeps everything else when the column goes`() {
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
}
