package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.Codex
import com.theoriacodex.domain.model.CodexItem
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.series.ExtensionRepo
import com.theoriacodex.domain.series.SourceOff
import com.theoriacodex.domain.source.SiteProfile

data class BackupLibrary(
    val codices: List<Codex>,
    val items: List<CodexItem>,
    val posts: List<Post>,
    val likes: List<LikedPost>,
    val watched: List<RecentPostEntry> = emptyList(),
    val searches: List<RecentSearchEntry> = emptyList(),
    val series: List<BackupSeries> = emptyList(),
)

data class BackupSeries(val series: StoredSeries, val entries: List<StoredSeriesEntry>)

interface ProfileBackupStore {
    suspend fun snapshot(includeRecents: Boolean): BackupLibrary
    suspend fun merge(library: BackupLibrary): Int
}

data class ProfileBackup(
    val createdAtEpochMs: Long,
    val settings: AppSettings,
    val library: BackupLibrary,
    val savedSearches: List<SavedSearchEntry> = emptyList(),
    val readingPositions: List<ReadingPosition> = emptyList(),
    val statistics: LifetimeStatistics? = null,
    val customSources: List<SiteProfile> = emptyList(),
    val extensionRepos: List<ExtensionRepo> = emptyList(),
) {
    fun preview(newRepos: List<ExtensionRepo> = emptyList()) = ProfileBackupPreview(
        createdAtEpochMs = createdAtEpochMs,
        profileNames = settings.recommendationProfiles.map(RecommendationProfile::name),
        collectionCount = library.codices.size,
        savedPostCount = library.items.map { it.postId }.distinct().size,
        likeCount = library.likes.size,
        savedSearchCount = savedSearches.size,
        recentCount = library.watched.size + library.searches.size,
        followedCreatorCount = settings.followedCreators.size,
        seriesCount = library.series.size,
        newRepos = newRepos.map(ExtensionRepo::name),
    )
}

sealed interface BackupPreview {
    data class Profiles(val preview: ProfileBackupPreview) : BackupPreview
    data class Series(
        val preview: SeriesImportPreview,
        val newRepos: List<String> = emptyList(),
        val turnOn: List<SourceOff> = emptyList(),
        val missingSources: List<String> = emptyList(),
        val unnamedMissingSources: Int = 0,
    ) : BackupPreview
}

data class SeriesImportPreview(val manga: Int, val anime: Int, val chaptersRead: Int, val episodesWatched: Int)

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
    val newRepos: List<String> = emptyList(),
)

data class ProfileRestoreResult(
    val profilesAdded: Int,
    val collectionsAdded: Int,
    val keptExistingSites: List<String> = emptyList(),
    val reposAdded: Int = 0,
    val repoProblems: Map<String, String> = emptyMap(),
)
