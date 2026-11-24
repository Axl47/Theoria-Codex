package com.theoriacodex.app.ui.routes

import com.theoriacodex.data.repository.GalleryReadingModeRepository
import com.theoriacodex.data.repository.InMemoryGalleryReadingModeRepository
import com.theoriacodex.domain.model.GalleryReadingMode
import kotlinx.coroutines.flow.combine

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.CodexRepository
import com.theoriacodex.data.repository.LikesRepository
import com.theoriacodex.data.repository.ProfileLibraryIds
import com.theoriacodex.data.repository.RecentsRepository
import com.theoriacodex.app.post.FileMirrorIndex
import com.theoriacodex.data.repository.RecentPostEntry
import com.theoriacodex.data.repository.RecentPostSection
import com.theoriacodex.data.repository.SettingsRepository
import com.theoriacodex.data.repository.mergeRecentActivity
import com.theoriacodex.domain.model.PostId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
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
    val codexPostIds: Set<PostId>,
    val galleryReadingModes: Map<PostId, GalleryReadingMode> = emptyMap(),
)

@OptIn(ExperimentalCoroutinesApi::class)
internal class BrowsingMetadataViewModel(
    settingsRepository: SettingsRepository,
    likes: LikesRepository,
    codices: CodexRepository,
    recentsRepository: RecentsRepository,
    scopeOverride: CoroutineScope? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val mirrors: FileMirrorIndex = FileMirrorIndex(),
    galleryReadingModes: GalleryReadingModeRepository = InMemoryGalleryReadingModeRepository(),
    private val pictureHashes: Flow<Map<PostId, Long>> = emptyFlow(),
) : ViewModel() {
    private val scope = scopeOverride ?: viewModelScope
    private val started = SharingStarted.WhileSubscribed(5_000L)
    private val settings = settingsRepository.observeSettings().flowOn(dispatcher).shareIn(scope, started, replay = 1)
    private val profileLikes = settings.map { it.activeRecommendationProfile().profileId }.distinctUntilChanged()
        .flatMapLatest { profileId -> likes.observeLikedPostIds(profileId).map { ProfileLikes(profileId, it) } }
        .shareIn(scope, started, replay = 1)
    private val codexIds = codices.observeCodices().map { rows -> rows.mapTo(linkedSetOf()) { it.codexId } }.distinctUntilChanged()
    private val saved = codexIds.flatMapLatest { ids ->
        val madeCodexIds = ids.filterNotTo(linkedSetOf()) { it.startsWith(ProfileLibraryIds.LIKES_PREFIX) }
        combine(codices.observeSavedPostIds(ids), codices.observeSavedPostIds(madeCodexIds), ::SavedIds)
    }.shareIn(scope, started, replay = 1)

    private val mirrorLinks = channelFlow {
        launch { mirrors.learn(recentsRepository.observeWatchedPosts().first().map(RecentPostEntry::post)) }
        launch { pictureHashes.collect(mirrors::learnPictures) }
        launch {
            val taught = HashSet<PostId>()
            combine(profileLikes, saved) { liked, savedIds -> liked.ids + savedIds.all }.collect { stored ->
                val fresh = stored.filter(taught::add)
                mirrors.learn(
                    if (fresh.size > SINGLE_READS) codices.observeLibraryPosts(codexIds.first()).first()
                    else fresh.mapNotNull { codices.getPost(it) },
                )
            }
        }
        mirrors.links.collect(::send)
    }

    val state = combine(settings, profileLikes, saved, recentsRepository.observeWatchedPostIds(), mirrorLinks) {
            current, membership, savedIds, watchedIds, _ ->
        if (current.activeRecommendationProfile().profileId != membership.profileId) null else BrowsingMetadata(
            current, mirrors.withCopies(membership.ids), mirrors.withCopies(savedIds.all), mirrors.withCopies(watchedIds),
            mirrors.withCopies(savedIds.inMadeCodices),
        )
    }.combine(galleryReadingModes.observe()) { metadata, modes -> metadata?.copy(galleryReadingModes = modes) }.filterNotNull().flowOn(dispatcher).stateIn(scope, started, null)

    val recents = combine(
        recentsRepository.observeWatchedPosts(),
        recentsRepository.observeSearches(),
        state.filterNotNull().map { it.likedPostIds }.distinctUntilChanged(),
    ) { watched, searches, likedIds ->
        RecentsDestinationState(
            watchedPosts = watched.filter { it.section == RecentPostSection.WATCHED },
            codexPosts = watched.filter { it.section == RecentPostSection.CODEX },
            searches = searches,
            activity = mergeRecentActivity(watched, searches),
            likedPostIds = likedIds,
        )
    }.flowOn(dispatcher).stateIn(scope, started, null)

    private data class SavedIds(val all: Set<PostId>, val inMadeCodices: Set<PostId>)

    private data class ProfileLikes(val profileId: String, val ids: Set<PostId>)

    private companion object {
        const val SINGLE_READS = 20
    }
}
