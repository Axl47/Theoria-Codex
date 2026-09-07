package com.theoriacodex.data.android.room

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomUpgradeJourneyDeviceTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), TheoriaRoomDatabase::class.java)

    @Test fun persistedUpgradePreservesLibraryHistoryAndSubsequentWrites() = runBlocking {
        for (version in listOf(1, 2, 6)) {
            RoomUpgradeScenario.verify(InstrumentationRegistry.getInstrumentation().targetContext, helper, version)
        }
    }
}
