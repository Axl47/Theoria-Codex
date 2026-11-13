package com.theoriacodex.app.ui.routes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.CodexRepository
import com.theoriacodex.data.repository.LikesRepository
import com.theoriacodex.data.repository.RecentsRepository
import com.theoriacodex.app.post.FileMirrorIndex
import com.theoriacodex.data.repository.RecentPostEntry
import com.theoriacodex.data.repository.RecentPostSection
import com.theoriacodex.data.repository.RecentSearchKind
import com.theoriacodex.data.repository.SettingsRepository
import com.theoriacodex.data.repository.mergeRecentActivity
import com.theoriacodex.domain.model.PostId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn

/**
 * Settings plus the posts whose file is liked, saved or watched: the stored IDs and every post known this session that
 * holds the same file on another site ([FileMirrorIndex]). The Library and like counts read the stored IDs instead.
 */
internal data class BrowsingMetadata(
    val settings: AppSettings,
    val likedPostIds: Set<PostId>,
    val savedPostIds: Set<PostId>,
    val watchedPostIds: Set<PostId>,
)

/**
 * One activity-owned read model is shared by every destination. Badges and filters read IDs only; the stored posts'
 * URLs reach [FileMirrorIndex] beside the first emission, so mirrors appear once those posts are decoded.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class BrowsingMetadataViewModel(
    settingsRepository: SettingsRepository,
    likes: LikesRepository,
    codices: CodexRepository,
    recentsRepository: RecentsRepository,
    scopeOverride: CoroutineScope? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val mirrors: FileMirrorIndex = FileMirrorIndex(),
) : ViewModel() {
    private val scope = scopeOverride ?: viewModelScope
    private val started = SharingStarted.WhileSubscribed(5_000L)
    private val settings = settingsRepository.observeSettings().flowOn(dispatcher).shareIn(scope, started, replay = 1)
    private val profileLikes = settings.map { it.activeRecommendationProfile().profileId }.distinctUntilChanged()
        .flatMapLatest { profileId -> likes.observeLikedPostIds(profileId).map { ProfileLikes(profileId, it) } }
    private val codexIds = codices.observeCodices().map { rows -> rows.mapTo(linkedSetOf()) { it.codexId } }.distinctUntilChanged()
    private val saved = codexIds.flatMapLatest(codices::observeSavedPostIds)
    private val watchedPosts = recentsRepository.observeWatchedPosts().shareIn(scope, started, replay = 1)

    /** [FileMirrorIndex.links], while teaching the index the stored posts for as long as metadata is observed. */
    private val mirrorLinks = channelFlow {
        launch { merge(codexIds.flatMapLatest(codices::observeLibraryPosts), watchedPosts.map { it.map(RecentPostEntry::post) }).collect(mirrors::learn) }
        mirrors.links.collect(::send)
    }

    val state = combine(settings, profileLikes, saved, recentsRepository.observeWatchedPostIds(), mirrorLinks) {
            current, membership, savedIds, watchedIds, _ ->
        // A profile switch must never pair the new profile with the previous profile's Likes.
        if (current.activeRecommendationProfile().profileId != membership.profileId) null else BrowsingMetadata(
            current, mirrors.withCopies(membership.ids), mirrors.withCopies(savedIds), mirrors.withCopies(watchedIds),
        )
    }.filterNotNull().flowOn(dispatcher).stateIn(scope, started, null)

    val recents = combine(
        watchedPosts,
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
