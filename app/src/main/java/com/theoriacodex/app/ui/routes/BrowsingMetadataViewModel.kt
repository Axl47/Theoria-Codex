package com.theoriacodex.app.ui.routes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.CodexRepository
import com.theoriacodex.data.repository.LikesRepository
import com.theoriacodex.data.repository.RecentsRepository
import com.theoriacodex.data.repository.RecentPostSection
import com.theoriacodex.data.repository.RecentSearchKind
import com.theoriacodex.data.repository.SettingsRepository
import com.theoriacodex.data.repository.mergeRecentActivity
import com.theoriacodex.domain.model.PostId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn

internal data class BrowsingMetadata(
    val settings: AppSettings,
    val likedPostIds: Set<PostId>,
    val savedPostIds: Set<PostId>,
    val watchedPostIds: Set<PostId>,
)

/** One navigation-owned read model is shared by pager destinations; no full Posts are read for badges or filters. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class BrowsingMetadataViewModel(
    settingsRepository: SettingsRepository,
    likes: LikesRepository,
    codices: CodexRepository,
    recentsRepository: RecentsRepository,
    scopeOverride: CoroutineScope? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val scope = scopeOverride ?: viewModelScope
    private val started = SharingStarted.WhileSubscribed(5_000L)
    private val settings = settingsRepository.observeSettings().flowOn(dispatcher).shareIn(scope, started, replay = 1)
    private val profileLikes = settings.map { it.activeRecommendationProfile().profileId }.distinctUntilChanged()
        .flatMapLatest { profileId -> likes.observeLikedPostIds(profileId).map { ProfileLikes(profileId, it) } }
    private val saved = codices.observeCodices().map { rows -> rows.mapTo(linkedSetOf()) { it.codexId } }
        .distinctUntilChanged().flatMapLatest(codices::observeSavedPostIds)

    val state = combine(settings, profileLikes, saved, recentsRepository.observeWatchedPostIds()) {
            current, membership, savedIds, watchedIds ->
        // A profile switch must never pair the new profile with the previous profile's Likes.
        if (current.activeRecommendationProfile().profileId != membership.profileId) null else
            BrowsingMetadata(current, membership.ids, savedIds, watchedIds)
    }.filterNotNull().flowOn(dispatcher).stateIn(scope, started, null)

    val recents = combine(
        recentsRepository.observeWatchedPosts(),
        recentsRepository.observeSearches(),
        state.filterNotNull().map { it.likedPostIds }.distinctUntilChanged(),
    ) { watched, searches, likedIds ->
        RecentsDestinationState(
            watchedPosts = watched.filter { it.section == RecentPostSection.WATCHED },
            codexPosts = watched.filter { it.section == RecentPostSection.CODEX },
            searches = searches.filterNot { it.kind == RecentSearchKind.FYP },
            fypSearches = searches.filter { it.kind == RecentSearchKind.FYP },
            activity = mergeRecentActivity(watched, searches),
            likedPostIds = likedIds,
        )
    }.flowOn(dispatcher).stateIn(scope, started, null)

    private data class ProfileLikes(val profileId: String, val ids: Set<PostId>)
}
