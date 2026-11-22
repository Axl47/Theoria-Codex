package com.theoriacodex.app.di

import com.theoriacodex.domain.series.ExtensionChoices
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DeviceExtensionChoicesTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `choices in the settings store move to the device once and only on an app updated in place`() = runTest {
        val inSettings = ExtensionChoices(trusted = mapOf("extension.a" to "ab12"), languages = mapOf("extension.a" to setOf("en")))
        var taken = 0
        fun store(name: String, updatedInPlace: Boolean) =
            DeviceExtensionChoices(File(folder.root, name), { taken += 1; inSettings }, { updatedInPlace })

        // The app updated over an earlier install: the user made these choices on this device.
        assertEquals(inSettings, store("kept.json", updatedInPlace = true).choices())
        // A fresh install whose settings came from Android's restore of another phone: nothing is on.
        val restored = store("restored.json", updatedInPlace = false)
        assertEquals(ExtensionChoices(), restored.choices())
        assertEquals(2, taken)

        restored.update { it.copy(trusted = mapOf("extension.b" to "cd34")) }
        restored.rememberExtensionSeen(true)
        val reopened = store("restored.json", updatedInPlace = true)
        assertTrue(reopened.extensionSeen)
        assertEquals(mapOf("extension.b" to "cd34"), reopened.choices().trusted)
        // The file exists, so the settings store is not asked again.
        assertEquals(2, taken)
    }
}
