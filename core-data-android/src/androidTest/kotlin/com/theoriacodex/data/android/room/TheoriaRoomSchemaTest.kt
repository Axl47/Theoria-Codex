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

    companion object {
        private const val TEST_DATABASE_NAME = "theoria-room-schema-test"
    }
}
