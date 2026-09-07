package com.theoriacodex.data.android.room

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
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
}
