package com.theoriacodex.data.android.room

import android.content.Context
import android.database.sqlite.SQLiteDatabase
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
        val current = createdVersion(context)
        context.deleteDatabase(name)
        helper.createDatabase(name, current).use {
            it.execSQL("INSERT INTO codices(codex_id,name,created_at_epoch_ms,display_order) VALUES('saved','Saved',1,0)")
            it.version = current + 1
        }
        val database = TheoriaRoomDatabase.create(context, name)
        val failure = runCatching { openProfileLibrary(database) }.exceptionOrNull()
        database.close()
        assertTrue(failure.toString(), failure is IllegalStateException)
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use {
            assertEquals(current + 1, it.version)
            it.rawQuery("SELECT name FROM codices", null).use { rows ->
                assertTrue(rows.moveToFirst())
                assertEquals("Saved", rows.getString(0))
            }
        }
        context.deleteDatabase(name)
    }

    @Test fun `version 13 adds a verdict log without a post and signatures that follow their post (ADR 0017)`() = RoomMigrationScenarios.verdictsAndSignaturesArrive(helper)

    @Test fun `version 14 removes spike rows from the seven named tables, keeps named Custom Sites and spares verdicts (ADR 0012)`() = RoomMigrationScenarios.spikeRowsGo(helper)

    @Test fun `version 14 removes searches naming a spike source in any source field and keeps ones that only mention it (ADR 0012)`() = RoomMigrationScenarios.spikeSearchesGo(helper)

    @Test fun `a series stored before chapter downloads keeps everything and does not download new chapters (ADR 0012)`() = RoomMigrationScenarios.downloadFlagArrivesOff(helper)

    @Test fun `version 16 keeps picture hashes as tried and adds empty vision columns (ADR 0012)`() = RoomMigrationScenarios.hashesStayTried(helper)

    @Test fun `version 17 marks every existing analysis as awaiting frames and nothing else (ADR 0012)`() = RoomMigrationScenarios.analysesAwaitFrames(helper)

    @Test fun `a series stored before shared first pages keeps everything and does not share them (ADR 0012)`() = RoomMigrationScenarios.sharingArrivesOff(helper)

    @Test fun `a series followed before follow times takes its first stored time, and nothing is read yet (ADR 0012)`() = RoomMigrationScenarios.followTimesFromFirstSeen(helper)

    @Test fun `a series and chapter stored before memos keep everything and have none (ADR 0012)`() = RoomMigrationScenarios.memosArriveEmpty(helper)

    @Test fun `a series stored with a check time keeps everything else when the column goes (ADR 0012)`() = RoomMigrationScenarios.checkTimeGoes(helper)

    @Test fun `the viewer translation cache goes and the library stays (ADR 0012)`() = RoomMigrationScenarios.translationCacheGoes(helper)

    @Test fun `a stored series starts with no check time, so the next background run checks it (ADR 0015)`() = RoomMigrationScenarios.checkTimeReturnsEmpty(helper)

    @Test fun `registered migrations step one version at a time from 10 to the version a new library is created at, each with a scenario (ADR 0012)`() {
        val created = createdVersion(ApplicationProvider.getApplicationContext<Context>())
        val steps = TheoriaRoomDatabase.MIGRATIONS.map { it.startVersion to it.endVersion }

        assertEquals((10 until created).map { it to it + 1 }, steps)
        assertEquals(steps.map { it.first }.toSet(), RoomMigrationScenarios.steps.keys)
    }

    private fun createdVersion(context: Context): Int {
        val name = "created-version.db"
        context.deleteDatabase(name)
        val database = TheoriaRoomDatabase.create(context, name)
        return try {
            database.openHelper.writableDatabase.version
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }
}
