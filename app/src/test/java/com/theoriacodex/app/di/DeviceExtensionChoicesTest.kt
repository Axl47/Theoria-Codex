package com.theoriacodex.app.di

import com.theoriacodex.domain.series.ExtensionChoices
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DeviceExtensionChoicesTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `nothing is on until it is turned on here and choices are kept in the device file (ADR 0189)`() = runTest {
        val file = File(folder.root, "extension_choices.json")
        val choices = DeviceExtensionChoices(file)
        assertEquals(ExtensionChoices(), choices.choices())

        choices.update { it.copy(trusted = mapOf("extension.b" to "cd34"), languages = mapOf("extension.b" to setOf("en"))) }

        val reopened = DeviceExtensionChoices(file)
        assertEquals(ExtensionChoices(mapOf("extension.b" to "cd34"), mapOf("extension.b" to setOf("en"))), reopened.choices())
    }
}
