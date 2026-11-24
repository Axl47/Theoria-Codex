package com.theoriacodex.app.ui

import com.theoriacodex.app.codex.CodexCoverCandidate
import com.theoriacodex.app.media.videoQualityLabel
import com.theoriacodex.app.search.omnibox.OmniboxEntry
import com.theoriacodex.app.search.omnibox.matchOmnibox
import com.theoriacodex.app.settings.SettingsAction
import com.theoriacodex.data.repository.ALL_SETTING_DEFINITIONS
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.RecentPostEntry
import com.theoriacodex.data.repository.SettingChoices
import com.theoriacodex.data.repository.SettingDestinations
import com.theoriacodex.data.repository.SettingToggle
import com.theoriacodex.data.repository.SettingToggles
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.model.VideoQuality
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesCovers
import com.theoriacodex.domain.series.SeriesId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
        val toggles = searchSettingEntries(settings, dispatch = written::add, open = {}).filterIsInstance<OmniboxEntry.Toggle>()

        assertEquals(SettingToggles.all.map { it.title }, toggles.map { it.title })
        assertEquals(SettingToggles.all.map { it.read(settings) }, toggles.map { it.checked })
        toggles.forEach { it.set(!it.checked) }
        assertEquals(SettingToggles.all.map { SettingsAction.SetToggle(it, !it.read(settings)) }, written)
    }

    @Test fun `the search box finds a toggle by its title and by a keyword`() {
        val toggles = searchSettingEntries(AppSettings(), dispatch = {}, open = {})

        assertEquals("Instant Seek", (matchOmnibox(toggles, "instant").first() as OmniboxEntry.Toggle).title)
        assertEquals("Download while roaming", (matchOmnibox(toggles, "roaming").first() as OmniboxEntry.Toggle).title)
        assertEquals("Auto-Rotate Videos", (matchOmnibox(toggles, "orientation").single() as OmniboxEntry.Toggle).title)
    }

    @Test fun `every setting definition is bound, and only toggles switch in place`() {
        ALL_SETTING_DEFINITIONS.forEach { definition ->
            val binding = SETTING_BINDINGS[definition.id]
            assertNotNull("${definition.id} has no search binding", binding)
            assertEquals(definition.id, definition is SettingToggle, binding == SettingBinding.Switch)
        }
    }

    @Test fun `a choice shows its current value and opens where it is picked`() {
        val defaults = AppSettings()
        val settings = defaults.copy(cache = defaults.cache.copy(downloadQuality = VideoQuality.DATA_SAVER))
        val opened = mutableListOf<SettingBinding>()
        val entries = searchSettingEntries(settings, dispatch = {}, open = opened::add)
        val choice = SettingChoices.DownloadQuality

        val entry = entries.filterIsInstance<OmniboxEntry.Action>().single { it.title == choice.title }
        assertEquals(videoQualityLabel(VideoQuality.DATA_SAVER), entry.detail)
        entry.run()
        assertEquals(listOf(SETTING_BINDINGS.getValue(choice.id)), opened)
        SettingChoices.all.forEach { assertNotNull(it.id, settingValueLabel(it.read(settings))) }
    }

    @Test fun `hidden, Search-owned and Viewer-owned definitions are left out`() {
        val titles = searchSettingEntries(AppSettings(), setOf(SettingDestinations.Diagnostics.id), {}, {}).map { it.title }

        assertFalse(SettingDestinations.Diagnostics.title in titles)
        assertFalse(SettingDestinations.SourceMix.title in titles)
        assertFalse(SettingChoices.DefaultVideoQuality.title in titles)
        assertTrue(SettingDestinations.Controls.title in titles)
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

    @Test fun `a followed series is found by its title, names its kind and source, and opens its page`() {
        val manga = Series(SeriesId(SourceKey.parseOrThrow("custom_mihon_1"), "/hero"), "The Hero", coverUrl = "https://cdn.test/hero.jpg")
        val anime = Series(SeriesId(SourceKey.parseOrThrow("custom_aniyomi_2"), "/hero-tv"), "Hero TV")
        var opened: SeriesId? = null

        val entries = followedSeriesEntries(listOf(manga, anime), mapOf(manga.id.source to "Ink Harbor"), SeriesCovers.Stored) { opened = it }
        val found = matchOmnibox(entries, "hero").filterIsInstance<OmniboxEntry.Action>()

        assertEquals("Hero TV starts with the text", listOf("Hero TV", "The Hero"), found.map { it.title })
        assertEquals(listOf("Anime", "Manga · Ink Harbor"), found.map { it.detail })
        assertEquals(listOf(CodexCoverCandidate.RemoteImage(manga.id.source, "https://cdn.test/hero.jpg")), found.last().cover)
        found.last().run()
        assertEquals(manga.id, opened)
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
