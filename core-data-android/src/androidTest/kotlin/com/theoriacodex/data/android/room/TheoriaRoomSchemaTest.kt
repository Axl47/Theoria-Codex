package com.theoriacodex.data.android.room

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
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

    companion object {
        private const val TEST_DATABASE_NAME = "theoria-room-schema-test"
    }
}
