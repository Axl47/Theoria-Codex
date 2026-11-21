package com.theoriacodex.app.ui

import com.theoriacodex.app.search.omnibox.OmniboxEntry
import com.theoriacodex.app.search.omnibox.matchOmnibox
import com.theoriacodex.app.settings.SettingsAction
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.RecentPostEntry
import com.theoriacodex.data.repository.SettingToggles
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
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

        assertEquals(SettingToggles.all.map { it.title }, toggles.map { it.title })
        assertEquals(SettingToggles.all.map { it.read(settings) }, toggles.map { it.checked })
        toggles.forEach { it.set(!it.checked) }
        assertEquals(SettingToggles.all.map { SettingsAction.SetToggle(it, !it.read(settings)) }, written)
    }

    @Test fun `the search box finds a toggle by its title and by a keyword`() {
        val toggles = searchToggleEntries(AppSettings()) {}

        assertEquals("Instant Seek", (matchOmnibox(toggles, "instant").first() as OmniboxEntry.Toggle).title)
        assertEquals("Download while roaming", (matchOmnibox(toggles, "roaming").first() as OmniboxEntry.Toggle).title)
        assertEquals("Auto-Rotate Videos", (matchOmnibox(toggles, "orientation").single() as OmniboxEntry.Toggle).title)
    }

    @Test fun `recent creators come latest first, once each, after the followed ones`() {
        val followed = CreatorProfile(SourceKey.PIXIV, "Aoi", profileId = "1")
        val beni = CreatorProfile(SourceKey.PIXIV, "Beni", profileId = "2")
        val chiyo = CreatorProfile(SourceKey.DANBOORU, "Chiyo")
        val watched = listOf(
            watched("old", viewedAt = 1, chiyo),
            watched("new", viewedAt = 3, beni, followed.copy(displayName = "Aoi (renamed)")),
            watched("middle", viewedAt = 2, beni),
        )

        assertEquals(listOf(beni, chiyo), recentCreators(watched, listOf(followed)))
        assertEquals(listOf(beni), recentCreators(watched, listOf(followed), limit = 1))
    }

    private fun watched(id: String, viewedAt: Long, vararg creators: CreatorProfile) = RecentPostEntry(
        post = Post(
            id = PostId(SourceKey.PIXIV, id),
            preview = ImageRef(url = "https://example.test/$id.jpg", localPath = null, mime = "image/jpeg"),
            full = null, pageUrl = null, width = 1, height = 1, canonicalTags = emptyList(), rawTags = emptyList(),
            authorName = null, createdAtEpochMs = null, creatorProfiles = creators.toList(),
        ),
        viewedAtEpochMs = viewedAt,
        origin = ViewerStreamSource.SEARCH,
        originQueryHash = null,
    )
}
