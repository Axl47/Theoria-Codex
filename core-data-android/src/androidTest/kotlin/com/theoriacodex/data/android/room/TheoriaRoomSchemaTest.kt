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
    fun migrationTwelveToThirteenAddsVerdictLogAndSignaturesThatFollowTheirPost() {
        migrationHelper.createDatabase(TEST_DATABASE_NAME, 12).apply {
            execSQL("INSERT INTO posts(source,source_post_id,payload_json) VALUES('PIXIV','1','{}')")
            close()
        }

        val database = migrationHelper.runMigrationsAndValidate(
            TEST_DATABASE_NAME,
            13,
            true,
            TheoriaRoomDatabase.MIGRATION_12_13,
        )
        database.execSQL(
            "INSERT INTO for_you_verdicts(profile_id,source,source_post_id,decision,decided_at_epoch_ms,preview_url) " +
                "VALUES('default','PIXIV','2','SKIPPED',1,NULL)",
        )
        database.execSQL("INSERT INTO post_visual_signatures(source,source_post_id,phash,indexed_at_epoch_ms) VALUES('PIXIV','1',94,1)")
        database.execSQL("PRAGMA foreign_keys=ON")
        database.execSQL("DELETE FROM posts WHERE source='PIXIV' AND source_post_id='1'")
        database.query("SELECT COUNT(*) FROM post_visual_signatures").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        database.query("SELECT decision FROM for_you_verdicts").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("SKIPPED", cursor.getString(0))
        }
        database.close()
    }

    @Test
    fun migrationFifteenToSixteenKeepsHashesAsTriedAndAddsEmptyVisionColumns() {
        migrationHelper.createDatabase(TEST_DATABASE_NAME, 15).apply {
            execSQL("INSERT INTO posts(source,source_post_id,payload_json) VALUES('PIXIV','1','{}')")
            execSQL("INSERT INTO post_visual_signatures(source,source_post_id,phash,indexed_at_epoch_ms) VALUES('PIXIV','1',94,1)")
            close()
        }

        val database = migrationHelper.runMigrationsAndValidate(
            TEST_DATABASE_NAME,
            16,
            true,
            TheoriaRoomDatabase.MIGRATION_15_16,
        )
        database.query("SELECT phash, phash_checked, model_id, embedding, predicted_tags FROM post_visual_signatures").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(94, cursor.getLong(0))
            assertEquals(1, cursor.getInt(1))
            for (column in 2..4) assertTrue(cursor.isNull(column))
        }
        database.close()
    }

    @Test
    fun migrationSixteenToSeventeenMarksEveryAnalysisAsAwaitingFrames() {
        migrationHelper.createDatabase(TEST_DATABASE_NAME, 16).apply {
            execSQL("INSERT INTO posts(source,source_post_id,payload_json) VALUES('PIXIV','1','{}')")
            execSQL("INSERT INTO posts(source,source_post_id,payload_json) VALUES('PIXIV','2','{}')")
            execSQL(
                "INSERT INTO post_visual_signatures(source,source_post_id,phash,phash_checked,indexed_at_epoch_ms,model_id,predicted_tags) " +
                    "VALUES('PIXIV','1',94,1,1,'m1','')",
            )
            execSQL("INSERT INTO post_visual_signatures(source,source_post_id,phash,indexed_at_epoch_ms) VALUES('PIXIV','2',95,1)")
            close()
        }

        val database = migrationHelper.runMigrationsAndValidate(
            TEST_DATABASE_NAME,
            17,
            true,
            TheoriaRoomDatabase.MIGRATION_16_17,
        )
        database.query("SELECT source_post_id, frame_state, frame_attempts FROM post_visual_signatures ORDER BY source_post_id").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(listOf("1", "1", "0"), listOf(cursor.getString(0), cursor.getString(1), cursor.getString(2)))
            assertTrue(cursor.moveToNext())
            assertEquals(listOf("2", "0", "0"), listOf(cursor.getString(0), cursor.getString(1), cursor.getString(2)))
        }
        database.close()
    }

    companion object {
        private const val TEST_DATABASE_NAME = "theoria-room-schema-test"
    }
}
