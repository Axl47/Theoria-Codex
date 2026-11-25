package com.theoriacodex.data.android.room

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
    fun migrationTwelveToThirteenAddsVerdictLogAndSignaturesThatFollowTheirPost() =
        RoomMigrationScenarios.verdictsAndSignaturesArrive(migrationHelper)

    @Test
    fun migrationFifteenToSixteenKeepsHashesAsTriedAndAddsEmptyVisionColumns() =
        RoomMigrationScenarios.hashesStayTried(migrationHelper)

    @Test
    fun migrationSixteenToSeventeenMarksEveryAnalysisAsAwaitingFrames() =
        RoomMigrationScenarios.analysesAwaitFrames(migrationHelper)

    @Test
    fun everyRegisteredMigrationKeepsAndChangesExactlyItsSeededRows() {
        for (scenario in RoomMigrationScenarios.steps.values) scenario(migrationHelper)
        RoomMigrationScenarios.spikeSearchesGo(migrationHelper)
    }
}
