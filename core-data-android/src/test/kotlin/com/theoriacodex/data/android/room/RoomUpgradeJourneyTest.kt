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
}
