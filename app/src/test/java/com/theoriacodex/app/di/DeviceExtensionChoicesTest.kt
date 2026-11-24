package com.theoriacodex.app.di

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.theoriacodex.domain.series.ExtensionChoices
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DeviceExtensionChoicesTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `nothing is on until it is turned on here and choices are kept in the device file`() = runTest {
        val file = File(folder.root, "extension_choices.json")
        val choices = DeviceExtensionChoices(file)
        assertEquals(ExtensionChoices(), choices.choices())

        choices.update { it.copy(trusted = mapOf("extension.b" to "cd34"), languages = mapOf("extension.b" to setOf("en"))) }

        val reopened = DeviceExtensionChoices(file)
        assertEquals(ExtensionChoices(mapOf("extension.b" to "cd34"), mapOf("extension.b" to setOf("en"))), reopened.choices())
    }

    @Test fun `extension choices live where Android neither backs them up nor transfers them (ADR 0007)`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()

        DeviceExtensionChoices.on(context).update { it.copy(trusted = mapOf("extension.b" to "cd34")) }

        assertTrue(File(context.noBackupFilesDir, "extension_choices.json").isFile)
    }
}
