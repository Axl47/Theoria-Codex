package com.theoriacodex.app.ui

import com.theoriacodex.app.settings.SettingsAction
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.RecentPostEntry
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
