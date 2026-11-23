package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.VideoQuality

/**
 * A setting as the app names it. Settings rows, controls moved next to their content, and the search box's
 * commands all read titles from here, so each label exists once. [keywords] are further words the search
 * box matches besides the title.
 */
sealed interface SettingDefinition {
    val id: String
    val title: String
    val keywords: List<String>
}

/**
 * An on/off setting. [read] gets it from [AppSettings] and [write] returns settings with it changed, so any
 * owner can apply it with `settingsRepository.updateSettings { toggle.write(it, checked) }`. [description]
 * is the line under the title, only where the title cannot say what the switch does.
 */
class SettingToggle(
    override val id: String,
    override val title: String,
    override val keywords: List<String>,
    val description: String? = null,
    val read: (AppSettings) -> Boolean,
    val write: (AppSettings, Boolean) -> AppSettings,
) : SettingDefinition

/** A setting with one of [options]; read and written like [SettingToggle]. Each surface labels the options. */
class SettingChoice<T>(
    override val id: String,
    override val title: String,
    override val keywords: List<String>,
    val options: List<T>,
    val read: (AppSettings) -> T,
    val write: (AppSettings, T) -> AppSettings,
) : SettingDefinition

/** A place that holds settings (a Settings page, a sheet); the app binds [id] to what opens it. */
data class SettingDestination(
    override val id: String,
    override val title: String,
    override val keywords: List<String> = emptyList(),
) : SettingDefinition

/** Every switch the app's settings offer. */
object SettingToggles {
    val OldSearchBar = SettingToggle(
        "old-search-bar", "Old Search Bar", listOf("pin search bar"), "Keep the search bar visible while scrolling.",
        read = { it.oldSearchBar }, write = { settings, on -> settings.copy(oldSearchBar = on) },
    )
    val InstantSeek = SettingToggle(
        "instant-seek", "Instant Seek", listOf("scrub", "seek"), "Seek while dragging instead of showing thumbnail previews.",
        read = { it.viewer.instantSeek }, write = { settings, on -> settings.copy(viewer = settings.viewer.copy(instantSeek = on)) },
    )
    val InvertScrollDirection = SettingToggle(
        "invert-scroll", "Invert Scroll Direction", listOf("reverse", "swipe"), "Reverse swipes in multi-media posts, except galleries given their own reading mode.",
        read = { it.viewer.invertMultiImageScrollDirection },
        write = { settings, on -> settings.copy(viewer = settings.viewer.copy(invertMultiImageScrollDirection = on)) },
    )
    val AutoRotateVideos = SettingToggle(
        "auto-rotate", "Auto-Rotate Videos", listOf("rotate", "orientation"),
        read = { it.viewer.autoRotateVideos }, write = { settings, on -> settings.copy(viewer = settings.viewer.copy(autoRotateVideos = on)) },
    )
    val DownloadsOverMetered = SettingToggle(
        "downloads-metered", "Download over metered networks", listOf("mobile data", "metered"),
        read = { it.cache.downloadsOverMetered },
        write = { settings, on -> settings.copy(cache = settings.cache.copy(downloadsOverMetered = on)) },
    )
    val DownloadsOverRoaming = SettingToggle(
        "downloads-roaming", "Download while roaming", listOf("roaming"),
        read = { it.cache.downloadsOverRoaming },
        write = { settings, on -> settings.copy(cache = settings.cache.copy(downloadsOverRoaming = on)) },
    )
    val KeepMediaOffline = SettingToggle(
        "keep-media-offline", "Keep media offline when saving", listOf("offline", "cache"),
        read = { it.cache.cacheFullImageOnSave },
        write = { settings, on -> settings.copy(cache = settings.cache.copy(cacheFullImageOnSave = on)) },
    )
    val FindAnimationDurations = SettingToggle(
        "find-durations", "Find animation durations in background", listOf("duration", "length"),
        read = { it.contentFilters.resolveUnknownAnimatedDurations },
        write = { settings, on -> settings.copy(contentFilters = settings.contentFilters.copy(resolveUnknownAnimatedDurations = on)) },
    )

    /** Only in a build with extensions, which alone has chapters to download. */
    val SeriesDeleteAfterReading = SettingToggle(
        "series-delete-after-reading", "Delete after reading", listOf("series", "chapters", "downloads"),
        read = { it.seriesDeleteAfterReading }, write = { settings, on -> settings.copy(seriesDeleteAfterReading = on) },
    )

    val all: List<SettingToggle> = listOf(
        OldSearchBar, InstantSeek, InvertScrollDirection, AutoRotateVideos,
        DownloadsOverMetered, DownloadsOverRoaming, KeepMediaOffline, FindAnimationDurations, SeriesDeleteAfterReading,
    )
}

/** Every choice the app's settings offer. */
object SettingChoices {
    val DefaultVideoQuality = SettingChoice(
        "video-quality", "Default video quality", listOf("resolution", "playback"), VideoQuality.entries,
        read = { it.viewer.videoQuality }, write = { settings, quality -> settings.copy(viewer = settings.viewer.copy(videoQuality = quality)) },
    )
    val DownloadQuality = SettingChoice(
        "download-quality", "Download quality", listOf("resolution", "downloads"), VideoQuality.entries,
        read = { it.cache.downloadQuality }, write = { settings, quality -> settings.copy(cache = settings.cache.copy(downloadQuality = quality)) },
    )

    /** Only in a build with extensions, which alone has series to check. */
    val SeriesUpdates = SettingChoice(
        "series-updates", "Check for new chapters", listOf("series", "notifications", "updates"), SeriesUpdateInterval.entries,
        read = { it.seriesUpdateInterval }, write = { settings, interval -> settings.copy(seriesUpdateInterval = interval) },
    )

    val all: List<SettingChoice<*>> = listOf(DefaultVideoQuality, DownloadQuality, SeriesUpdates)
}

/** Every place that holds settings, as Settings and the search box name it. */
object SettingDestinations {
    val Home = SettingDestination("settings", "Settings", listOf("preferences", "options"))
    val Stats = SettingDestination("stats", "Your stats", listOf("stats", "statistics", "usage"))
    val ShareStats = SettingDestination("share-stats", "Share my stats")
    val CompareStats = SettingDestination("compare-stats", "Compare with a friend")
    val HiddenTags = SettingDestination("hidden-tags", "Hidden tags", listOf("blacklist", "blocked", "mute"))
    val SourceMix = SettingDestination("source-mix", "Source mix", listOf("weights", "sources"))
    val Sites = SettingDestination("sites", "Sites", listOf("sources", "accounts", "sign in", "log in"))
    val Downloads = SettingDestination("downloads", "Downloads", listOf("download", "quality", "metered", "roaming"))
    val Controls = SettingDestination("controls", "Controls", listOf("gestures", "swipes"))
    val PictureTags = SettingDestination("picture-tags", "Picture tags", listOf("tagger", "model", "on-device", "image", "ai"))
    val Storage = SettingDestination("storage", "Storage & caching", listOf("cache", "space", "clear"))
    val Backup = SettingDestination("backup", "Backup", listOf("restore", "export", "import"))
    val WhatsNew = SettingDestination("whats-new", "What's new", listOf("changelog", "release notes", "version"))
    val Diagnostics = SettingDestination("diagnostics", "Diagnostics report", listOf("diagnostics", "logs", "errors"))

    val all: List<SettingDestination> = listOf(
        Home, Stats, ShareStats, CompareStats, HiddenTags, SourceMix, Sites, Downloads, Controls, PictureTags, Storage, Backup, WhatsNew,
        Diagnostics,
    )
}

/** All definitions; ids are unique across kinds. */
val ALL_SETTING_DEFINITIONS: List<SettingDefinition> = SettingToggles.all + SettingChoices.all + SettingDestinations.all
