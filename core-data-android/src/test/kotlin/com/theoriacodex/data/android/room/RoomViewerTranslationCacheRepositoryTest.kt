package com.theoriacodex.data.android.room

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.theoriacodex.data.repository.ViewerOcrLanguage
import com.theoriacodex.data.repository.ViewerTranslationCacheKey
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RoomViewerTranslationCacheRepositoryTest {
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
    fun `cache persists only source hash and expires entries`() = runTest {
        val repository = RoomViewerTranslationCacheRepository(
            database = database,
            ttlMs = 50L,
            clock = { now },
        )
        val key = key("秘密の日本語")

        repository.putAll(mapOf(key to "Secret Japanese"))

        assertEquals(mapOf(key to "Secret Japanese"), repository.getAll(setOf(key)))
        database.openHelper.writableDatabase.query("SELECT * FROM viewer_translation_cache").use { cursor ->
            assertTrue(cursor.moveToFirst())
            val columns = (0 until cursor.columnCount).map(cursor::getColumnName)
            assertFalse(columns.contains("source_text"))
            assertEquals(64, cursor.getString(cursor.getColumnIndexOrThrow("source_text_sha256")).length)
        }
        now = 150L
        assertTrue(repository.getAll(setOf(key)).isEmpty())
        assertEquals(0, database.viewerTranslationCacheDao().count())
    }

    @Test
    fun `least recently used rows are trimmed deterministically`() = runTest {
        val repository = RoomViewerTranslationCacheRepository(
            database = database,
            maxEntries = 2,
            clock = { now },
        )
        val first = key("first")
        val second = key("second")
        val third = key("third")
        repository.putAll(mapOf(first to "one", second to "two"))
        now += 1L
        repository.getAll(setOf(first))
        now += 1L
        repository.putAll(mapOf(third to "three"))

        assertEquals(
            mapOf(first to "one", third to "three"),
            repository.getAll(setOf(first, second, third)),
        )
    }

    @Test
    fun `migration seven to eight preserves content and creates cache schema`() {
        migrationHelper.createDatabase(MIGRATION_DATABASE_NAME, 7).apply {
            execSQL("INSERT INTO codices(codex_id,name,created_at_epoch_ms,display_order) VALUES('saved','Saved',1,0)")
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            MIGRATION_DATABASE_NAME,
            8,
            true,
            TheoriaRoomDatabase.MIGRATION_7_8,
        )
        migrated.execSQL(
            "INSERT INTO viewer_translation_cache(backend_version,source_language," +
                "source_text_sha256,translated_text,expires_at_epoch_ms,last_used_at_epoch_ms) " +
                "VALUES('google-nmt-v1','JAPANESE','hash','English',100,1)",
        )
        migrated.query("SELECT name FROM codices WHERE codex_id='saved'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Saved", cursor.getString(0))
        }
        migrated.close()
    }

    private fun key(text: String) = ViewerTranslationCacheKey(
        backendVersion = "google-nmt-v1",
        sourceLanguage = ViewerOcrLanguage.JAPANESE,
        sourceText = text,
    )

    private companion object {
        const val MIGRATION_DATABASE_NAME = "viewer-translation-cache-migration-test"
    }
}
