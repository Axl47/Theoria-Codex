package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.Codex
import com.theoriacodex.domain.model.CodexItem
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.source.SiteProfile

/** A logical transaction snapshot, independent of Room's tables and database version. */
data class BackupLibrary(
    val codices: List<Codex>,
    val items: List<CodexItem>,
    val posts: List<Post>,
    val likes: List<LikedPost>,
    val watched: List<RecentPostEntry> = emptyList(),
    val searches: List<RecentSearchEntry> = emptyList(),
    /** Absent from backups made before series existed. */
    val series: List<BackupSeries> = emptyList(),
)

/**
 * A series the user follows, started reading or chose a reading mode for, with every entry stored for it and the
 * progress through each. Series belong to the whole app, not to a profile.
 */
data class BackupSeries(val series: StoredSeries, val entries: List<StoredSeriesEntry>)

interface ProfileBackupStore {
    suspend fun snapshot(includeRecents: Boolean): BackupLibrary
    /**
     * Imports the entire library atomically, keeping existing membership and post payloads. A series already stored
     * keeps what it has: it only becomes followed if the backup followed it, takes the backup's reading mode when it
     * has none, and gains the entries it lacks. Returns collections added.
     */
    suspend fun merge(library: BackupLibrary): Int
}

data class ProfileBackup(
    val createdAtEpochMs: Long,
    val settings: AppSettings,
    val library: BackupLibrary,
    val savedSearches: List<SavedSearchEntry> = emptyList(),
    val readingPositions: List<ReadingPosition> = emptyList(),
    /** Absent from older backups and from backups made without history. */
    val statistics: LifetimeStatistics? = null,
    /** User-added sites. Credentials are never part of a backup. */
    val customSources: List<SiteProfile> = emptyList(),
) {
    fun preview() = ProfileBackupPreview(
        createdAtEpochMs = createdAtEpochMs,
        profileNames = settings.recommendationProfiles.map(RecommendationProfile::name),
        collectionCount = library.codices.size,
        savedPostCount = library.items.map { it.postId }.distinct().size,
        likeCount = library.likes.size,
        savedSearchCount = savedSearches.size,
        recentCount = library.watched.size + library.searches.size,
        followedCreatorCount = settings.followedCreators.size,
        seriesCount = library.series.size,
    )
}

/** What restoring a chosen file would add: a Theoria backup's profiles, or the series of a Mihon-family app's backup. */
sealed interface BackupPreview {
    data class Profiles(val preview: ProfileBackupPreview) : BackupPreview
    data class Series(val preview: SeriesImportPreview) : BackupPreview
}

/** What importing a Mihon-family backup adds: the series it follows by kind, the entries it finished, and its extension repos. */
data class SeriesImportPreview(
    val manga: Int,
    val anime: Int,
    val chaptersRead: Int,
    val episodesWatched: Int,
    /** How many extension repos the backup lists; importing adds them. */
    val repos: Int = 0,
    /** The backup's names for the sources its series are on that are not on here, the one with the most series first. */
    val missingSources: List<String> = emptyList(),
)

/** What a Mihon-family import did: what it [imported], how many of its repos are new here, and why each refused repo was, by address. */
data class SeriesImportResult(
    val imported: SeriesImportPreview,
    val reposAdded: Int = 0,
    val repoProblems: Map<String, String> = emptyMap(),
)

data class ProfileBackupPreview(
    val createdAtEpochMs: Long,
    val profileNames: List<String>,
    val collectionCount: Int,
    val savedPostCount: Int,
    val likeCount: Int,
    val savedSearchCount: Int,
    val recentCount: Int,
    val followedCreatorCount: Int,
    val seriesCount: Int = 0,
)

/** [keptExistingSites]: ids of sites kept as already configured here, though the backup's address or family differed. */
data class ProfileRestoreResult(
    val profilesAdded: Int,
    val collectionsAdded: Int,
    val keptExistingSites: List<String> = emptyList(),
)
