package com.theoriacodex.app.ui

import com.theoriacodex.app.settings.SettingsAction
import com.theoriacodex.data.repository.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchOmniboxCommandsTest {
    @Test fun `each toggle shows its preference and switching it writes that preference`() {
        val defaults = AppSettings()
        val settings = defaults.copy(
            oldSearchBar = true,
            viewer = defaults.viewer.copy(instantSeek = true, autoRotateVideos = false, invertMultiImageScrollDirection = true),
            cache = defaults.cache.copy(downloadsOverMetered = false, downloadsOverRoaming = true),
        )
        val written = mutableListOf<SettingsAction>()
        val toggles = searchToggleEntries(settings, written::add)

        assertEquals(
            mapOf(
                "Instant Seek" to true,
                "Auto-Rotate Videos" to false,
                "Invert Scroll Direction" to true,
                "Old Search Bar" to true,
                "Download over metered networks" to false,
                "Download while roaming" to true,
            ),
            toggles.associate { it.title to it.checked },
        )
        toggles.forEach { it.set(!it.checked) }
        assertEquals(
            listOf(
                SettingsAction.SetInstantSeek(false),
                SettingsAction.SetAutoRotateVideos(true),
                SettingsAction.SetInvertScrollDirection(false),
                SettingsAction.SetOldSearchBar(false),
                SettingsAction.SetDownloadsOverMetered(true),
                SettingsAction.SetDownloadsOverRoaming(false),
            ),
            written,
        )
    }
}
