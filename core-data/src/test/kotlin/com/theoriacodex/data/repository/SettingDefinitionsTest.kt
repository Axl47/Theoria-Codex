package com.theoriacodex.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingDefinitionsTest {
    @Test fun `every definition id is unique`() {
        val ids = ALL_SETTING_DEFINITIONS.map(SettingDefinition::id)
        assertEquals(ids.distinct(), ids)
    }

    @Test fun `each toggle reads back what it writes`() {
        SettingToggles.all.forEach { toggle ->
            listOf(true, false).forEach { on ->
                assertEquals(toggle.title, on, toggle.read(toggle.write(AppSettings(), on)))
            }
        }
    }

    @Test fun `each choice reads back every option it writes`() {
        SettingChoices.all.forEach { it.assertRoundTrips() }
    }

    private fun <T> SettingChoice<T>.assertRoundTrips() =
        options.forEach { option -> assertEquals(title, option, read(write(AppSettings(), option))) }
}
