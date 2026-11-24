package com.theoriacodex.data.android.room

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
        RoomUpgradeScenario.verify(ApplicationProvider.getApplicationContext<Context>(), helper, 1)
    }

    @Test fun `legacy recents and automatic groups survive skipped releases and later writes`() = runTest {
        for (version in listOf(2, 6)) {
            RoomUpgradeScenario.verify(ApplicationProvider.getApplicationContext<Context>(), helper, version)
        }
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
            database.query("SELECT address, followed_at_epoch_ms, last_read_at_epoch_ms FROM series ORDER BY address").use {
                val rows = buildList { while (it.moveToNext()) add(listOf(it.getString(0), it.getString(1), it.getString(2))) }
                assertEquals(listOf(listOf("/a", "10", null), listOf("/b", null, null)), rows)
            }
        }
    }
}
