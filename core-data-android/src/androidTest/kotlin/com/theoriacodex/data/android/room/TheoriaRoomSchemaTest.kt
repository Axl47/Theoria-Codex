package com.theoriacodex.data.android.room

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.runners.Enclosed
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Enclosed::class)
class TheoriaRoomSchemaTest {
    @RunWith(AndroidJUnit4::class)
    class Original {
        @get:Rule
        val migrationHelper = migrationHelper()

        @Test
        fun migrationTwelveToThirteenAddsVerdictLogAndSignaturesThatFollowTheirPost() =
            RoomMigrationScenarios.verdictsAndSignaturesArrive(migrationHelper)

        @Test
        fun migrationFifteenToSixteenKeepsHashesAsTriedAndAddsEmptyVisionColumns() =
            RoomMigrationScenarios.hashesStayTried(migrationHelper)

        @Test
        fun migrationSixteenToSeventeenMarksEveryAnalysisAsAwaitingFrames() =
            RoomMigrationScenarios.analysesAwaitFrames(migrationHelper)

        @Test
        fun migrationThirteenToFourteenRemovesSearchesNamingASpikeSource() =
            RoomMigrationScenarios.spikeSearchesGo(migrationHelper)
    }

    @RunWith(Parameterized::class)
    class EveryStep(private val from: Int) {
        @get:Rule
        val migrationHelper = migrationHelper()

        @Test
        fun migrationKeepsAndChangesExactlyItsSeededRows() = RoomMigrationScenarios.steps.getValue(from)(migrationHelper)

        companion object {
            @JvmStatic
            @Parameterized.Parameters(name = "from {0}")
            fun steps(): List<Int> = RoomMigrationScenarios.steps.keys.sorted()
        }
    }
}

private fun migrationHelper() = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), TheoriaRoomDatabase::class.java)
