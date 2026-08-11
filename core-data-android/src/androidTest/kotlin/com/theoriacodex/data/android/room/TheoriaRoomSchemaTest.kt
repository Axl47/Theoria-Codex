package com.theoriacodex.data.android.room

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TheoriaRoomSchemaTest {
    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        TheoriaRoomDatabase::class.java,
    )

    @Test
    fun versionOneSchemaContainsTheCompleteMigrationProof() {
        val database = migrationHelper.createDatabase(TEST_DATABASE_NAME, 1)
        val columns = buildSet {
            database.query("PRAGMA table_info(`migration_metadata`)").use { cursor ->
                val nameColumn = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) add(cursor.getString(nameColumn))
            }
        }
        database.close()

        assertTrue(
            columns.containsAll(
                setOf(
                    "source_fingerprint_sha256",
                    "destination_fingerprint_sha256",
                    "proof_sha256",
                    "codex_source_present",
                    "likes_source_present",
                    "codex_archived",
                    "likes_archived",
                )
            )
        )
    }

    @Test
    fun migrationOneToTwoPreservesContentAndCreatesRecentsOwnership() {
        migrationHelper.createDatabase(TEST_DATABASE_NAME, 1).apply {
            execSQL("INSERT INTO codices(codex_id,name,created_at_epoch_ms,display_order) VALUES('saved','Saved',1,0)")
            close()
        }

        val database = migrationHelper.runMigrationsAndValidate(
            TEST_DATABASE_NAME,
            2,
            true,
            TheoriaRoomDatabase.MIGRATION_1_2,
        )
        val tables = buildSet {
            database.query("SELECT name FROM sqlite_master WHERE type='table'").use { cursor ->
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
        database.query("SELECT name FROM codices WHERE codex_id='saved'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Saved", cursor.getString(0))
        }
        database.close()

        assertTrue(tables.containsAll(setOf("recent_watched", "recent_searches", "recents_migration_metadata")))
    }

    @Test
    fun migrationTwoToThreePreservesRowsAndAddsIndependentSectionIdentity() {
        migrationHelper.createDatabase(TEST_DATABASE_NAME, 2).apply {
            execSQL("INSERT INTO posts(source,source_post_id,payload_json) VALUES('PIXIV','watched','{}')")
            execSQL("INSERT INTO posts(source,source_post_id,payload_json) VALUES('PIXIV','codex','{}')")
            execSQL("INSERT INTO recent_watched(source,source_post_id,viewed_at_epoch_ms,sort_sequence,origin,origin_query_hash) VALUES('PIXIV','watched',10,1,'SEARCH','search')")
            execSQL("INSERT INTO recent_watched(source,source_post_id,viewed_at_epoch_ms,sort_sequence,origin,origin_query_hash) VALUES('PIXIV','codex',11,2,'CODEX','codex')")
            close()
        }

        val database = migrationHelper.runMigrationsAndValidate(
            TEST_DATABASE_NAME,
            3,
            true,
            TheoriaRoomDatabase.MIGRATION_2_3,
        )
        val memberships = linkedMapOf<String, String>()
        database.query("SELECT source_post_id, section FROM recent_watched ORDER BY source_post_id").use { cursor ->
            while (cursor.moveToNext()) memberships[cursor.getString(0)] = cursor.getString(1)
        }
        database.close()

        assertEquals(mapOf("codex" to "CODEX", "watched" to "WATCHED"), memberships)
    }

    @Test
    fun migrationThreeToFourPreservesCodicesAndAddsAutomaticTagOwnership() {
        migrationHelper.createDatabase(TEST_DATABASE_NAME, 3).apply {
            execSQL("INSERT INTO codices(codex_id,name,created_at_epoch_ms,display_order) VALUES('saved','Saved',1,0)")
            close()
        }

        val database = migrationHelper.runMigrationsAndValidate(
            TEST_DATABASE_NAME,
            4,
            true,
            TheoriaRoomDatabase.MIGRATION_3_4,
        )
        database.execSQL("PRAGMA foreign_keys = ON")
        database.execSQL("INSERT INTO codex_automatic_tags(codex_id,source,tag_key,tag_display) VALUES('saved','PIXIV','landscape','landscape')")
        database.query("SELECT name FROM codices WHERE codex_id='saved'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Saved", cursor.getString(0))
        }
        database.execSQL("DELETE FROM codices WHERE codex_id='saved'")
        database.query("SELECT COUNT(*) FROM codex_automatic_tags").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        database.close()
    }

    @Test
    fun migrationFourToFivePreservesContentAndAddsIndependentDurationOwnership() {
        migrationHelper.createDatabase(TEST_DATABASE_NAME, 4).apply {
            execSQL("INSERT INTO codices(codex_id,name,created_at_epoch_ms,display_order) VALUES('saved','Saved',1,0)")
            close()
        }

        val database = migrationHelper.runMigrationsAndValidate(
            TEST_DATABASE_NAME,
            5,
            true,
            TheoriaRoomDatabase.MIGRATION_4_5,
        )
        database.execSQL(
            "INSERT INTO media_durations(source,source_post_id,media_fingerprint,decision,duration_ms,provenance,updated_at_epoch_ms) " +
                "VALUES('HITOMI','anime','fingerprint','KNOWN',12000,'PROVIDER',1)",
        )
        database.query("SELECT name FROM codices WHERE codex_id='saved'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Saved", cursor.getString(0))
        }
        database.query("SELECT duration_ms FROM media_durations WHERE source_post_id='anime'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(12_000L, cursor.getLong(0))
        }
        database.close()
    }

    @Test
    fun migrationFiveToSixPreservesWatchedRowsAndDefaultsMediaProgress() {
        migrationHelper.createDatabase(TEST_DATABASE_NAME, 5).apply {
            execSQL("INSERT INTO posts(source,source_post_id,payload_json) VALUES('PIXIV','watched','{}')")
            execSQL(
                "INSERT INTO recent_watched(source,source_post_id,section,viewed_at_epoch_ms,sort_sequence,origin,origin_query_hash) " +
                    "VALUES('PIXIV','watched','WATCHED',10,1,'SEARCH','search')",
            )
            close()
        }

        val database = migrationHelper.runMigrationsAndValidate(
            TEST_DATABASE_NAME,
            6,
            true,
            TheoriaRoomDatabase.MIGRATION_5_6,
        )
        database.query(
            "SELECT max_viewed_media_number FROM recent_watched WHERE source_post_id='watched'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
        database.close()
    }

    @Test
    fun migrationSixToSevenPreservesAutomaticRulesAsOneOrGroupPerSource() {
        migrationHelper.createDatabase(TEST_DATABASE_NAME, 6).apply {
            execSQL("INSERT INTO codices(codex_id,name,created_at_epoch_ms,display_order) VALUES('saved','Saved',1,0)")
            execSQL("INSERT INTO codex_automatic_tags(codex_id,source,tag_key,tag_display) VALUES('saved','PIXIV','tag1','tag1')")
            execSQL("INSERT INTO codex_automatic_tags(codex_id,source,tag_key,tag_display) VALUES('saved','PIXIV','tag2','tag2')")
            close()
        }

        val database = migrationHelper.runMigrationsAndValidate(
            TEST_DATABASE_NAME,
            7,
            true,
            TheoriaRoomDatabase.MIGRATION_6_7,
        )
        database.query("SELECT group_index FROM codex_automatic_tags ORDER BY tag_key").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
            assertTrue(cursor.moveToNext())
            assertEquals(0, cursor.getInt(0))
        }
        database.close()
    }

    companion object {
        private const val TEST_DATABASE_NAME = "theoria-room-schema-test"
    }
}
