package com.theoriacodex.data.android.room

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.theoriacodex.data.repository.StoredMediaDurationKey
import com.theoriacodex.data.repository.StoredMediaDurationState
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RoomMediaDurationRepositoryTest {
    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        TheoriaRoomDatabase::class.java,
    )

    private lateinit var database: TheoriaRoomDatabase
    private var now = 100L

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            TheoriaRoomDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `typed decisions round trip without media request data`() = runTest {
        val repository = RoomMediaDurationRepository(database, clock = { now })
        val known = StoredMediaDurationState.Known(12_000L, "ACTIVE_PLAYER")
        val unsupported = StoredMediaDurationState.Unsupported("PREVIEW_ONLY_MEDIA")
        val retryable = StoredMediaDurationState.RetryableFailure(500L, "TIMEOUT")

        repository.put(key("known"), known)
        repository.put(key("unsupported"), unsupported)
        repository.put(key("retryable"), retryable)

        assertEquals(known, repository.get(key("known")))
        assertEquals(unsupported, repository.get(key("unsupported")))
        assertEquals(retryable, repository.get(key("retryable")))
        database.openHelper.writableDatabase.query("PRAGMA table_info(`media_durations`)").use { cursor ->
            val names = buildSet {
                val nameColumn = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) add(cursor.getString(nameColumn))
            }
            assertEquals(
                setOf(
                    "source",
                    "source_post_id",
                    "media_fingerprint",
                    "decision",
                    "duration_ms",
                    "provenance",
                    "reason",
                    "retry_at_epoch_ms",
                    "updated_at_epoch_ms",
                ),
                names,
            )
        }
    }

    @Test
    fun `expired retryable decision is deleted on lookup`() = runTest {
        val repository = RoomMediaDurationRepository(database, clock = { now })
        val key = key("retry")
        repository.put(key, StoredMediaDurationState.RetryableFailure(200L, "TIMEOUT"))
        now = 200L

        assertNull(repository.get(key))
        assertEquals(0, database.mediaDurationDao().count())
    }

    @Test
    fun `upsert replaces one fingerprint and pruning keeps the deterministic newest rows`() = runTest {
        val repository = RoomMediaDurationRepository(database, maxEntries = 2, clock = { now })
        repository.put(key("old"), StoredMediaDurationState.Unsupported("PROVIDER_UNSUPPORTED"))
        now += 1L
        repository.put(key("new-a"), StoredMediaDurationState.Unsupported("PROVIDER_UNSUPPORTED"))
        repository.put(key("new-b"), StoredMediaDurationState.Unsupported("PROVIDER_UNSUPPORTED"))
        repository.put(key("new-b"), StoredMediaDurationState.Known(3_000L, "PROVIDER"))

        assertEquals(2, database.mediaDurationDao().count())
        assertNull(repository.get(key("old")))
        assertEquals(
            StoredMediaDurationState.Known(3_000L, "PROVIDER"),
            repository.get(key("new-b")),
        )
    }

    @Test
    fun `migration four to five preserves content and validates duration schema`() {
        migrationHelper.createDatabase(MIGRATION_DATABASE_NAME, 4).apply {
            execSQL(
                "INSERT INTO codices(codex_id,name,created_at_epoch_ms,display_order) " +
                    "VALUES('saved','Saved',1,0)",
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            MIGRATION_DATABASE_NAME,
            5,
            true,
            TheoriaRoomDatabase.MIGRATION_4_5,
        )
        migrated.execSQL(
            "INSERT INTO media_durations(source,source_post_id,media_fingerprint,decision," +
                "duration_ms,provenance,updated_at_epoch_ms) " +
                "VALUES('HITOMI','anime','fingerprint','KNOWN',12000,'PROVIDER',1)",
        )
        migrated.query("SELECT name FROM codices WHERE codex_id='saved'").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals("Saved", cursor.getString(0))
        }
        migrated.close()
    }

    private fun key(id: String): StoredMediaDurationKey {
        return StoredMediaDurationKey(
            postId = PostId(SourceKey.HITOMI, id),
            mediaFingerprint = "fingerprint-$id",
        )
    }

    private companion object {
        const val MIGRATION_DATABASE_NAME = "media-duration-migration-test"
    }
}
