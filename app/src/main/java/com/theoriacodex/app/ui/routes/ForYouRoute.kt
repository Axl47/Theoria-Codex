package com.theoriacodex.app.ui.routes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.theoriacodex.app.recommend.ForYouCoordinator
import com.theoriacodex.app.recommend.ForYouScreen
import com.theoriacodex.app.recommend.ForYouViewModel
import com.theoriacodex.app.media.AnimatedDurationEnricher
import com.theoriacodex.app.recommend.state.ForYouAction
import com.theoriacodex.app.recommend.state.ForYouEffect
import com.theoriacodex.app.recommend.state.ForYouUiState
import com.theoriacodex.app.viewer.PixivUgoiraClient
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.ForYouBlacklistEntry
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SourceKey
import java.io.Closeable
import kotlinx.coroutines.flow.StateFlow

/** App-level inputs rendered by For You or used to reconcile recommendation inputs. */
internal data class ForYouRouteConfig(
    val settings: AppSettings,
    val activeProfileLikesCount: Int,
    val availableSources: Set<SourceKey>,
    val creatorBrowsingSources: Set<SourceKey>,
    val likedPostIds: Set<PostId>,
    val resolveUnknownAnimatedDurations: Boolean,
)

/** Platform and navigation effects that remain owned by the application shell. */
internal data class ForYouRouteCallbacks(
    val onOpenViewer: suspend (ForYouEffect.OpenViewer) -> Unit,
    val onNavigateToSearch: suspend () -> Unit,
    val onShowMessage: (String) -> Unit,
    val onSeedHidden: suspend (profileId: String, entries: List<ForYouBlacklistEntry>) -> Boolean,
    val onToggleLike: (Post) -> Unit,
    val onRequestSaveToCodex: (Post) -> Unit,
    val onSaveToDevice: (Post) -> Unit,
    val onOpenCreatorProfile: (CreatorProfile) -> Unit,
    val onOpenLegacyCreatorProfile: (Post) -> Unit,
    val onAddIncludeTerm: (Post, SearchTerm) -> Boolean,
    val onAddExcludeTerm: (Post, SearchTerm) -> Boolean,
    val onRemoveIncludeTerm: (Post, SearchTerm) -> Unit,
    val onRemoveExcludeTerm: (Post, SearchTerm) -> Unit,
    val onFavoriteTagLongPress: (SourceKey, String) -> Unit,
    val onGoToSearch: () -> Unit,
)

/** Non-owning access used by Viewer and other destinations while Home owns the ViewModel. */
internal class ForYouRouteOwnerHandle(
    owner: ForYouViewModel,
) : ObservableRouteOwnerHandle {
    private val ownerLease = owner.createRouteOwnerLease()

    /** Read-only state while the navigation-owned ViewModel remains alive. */
    val state: StateFlow<ForYouUiState>?
        get() = ownerLease.withOwner { activeOwner -> activeOwner.state }

    override fun invokeOnOwnerCleared(listener: () -> Unit): Closeable {
        return ownerLease.invokeOnClose(listener)
    }

    fun dispatch(action: ForYouAction): Boolean {
        return ownerLease.withOwner { activeOwner ->
            activeOwner.onAction(action)
            true
        } ?: false
    }

    fun rememberResolvedPost(post: Post): Boolean {
        return ownerLease.withOwner { activeOwner ->
            activeOwner.rememberResolvedPost(post)
            true
        } ?: false
    }

    fun currentState(): ForYouUiState? = ownerLease.withOwner { activeOwner -> activeOwner.state.value }
}

/** Navigation-scoped For You owner plus its immutable rendering boundary. */
@Composable
internal fun ForYouRoute(
    coordinator: ForYouCoordinator,
    animatedDurationEnricher: AnimatedDurationEnricher,
    pixivUgoiraClient: PixivUgoiraClient?,
    config: ForYouRouteConfig,
    callbacks: ForYouRouteCallbacks,
    onOwnerAvailable: (ForYouRouteOwnerHandle) -> Unit = {},
) {
    val owner = viewModel<ForYouViewModel>(
        key = FOR_YOU_ROUTE_OWNER_KEY,
        factory = ForYouViewModel.factory(
            coordinator = coordinator,
            initialProfiles = config.settings.recommendationProfiles,
            animatedDurationEnricher = animatedDurationEnricher,
        ),
    )
    val state by owner.state.collectAsStateWithLifecycle()
    val ownerHandle = remember(owner) { ForYouRouteOwnerHandle(owner) }
    val currentOnOwnerAvailable by rememberUpdatedState(onOwnerAvailable)

    SideEffect { currentOnOwnerAvailable(ownerHandle) }

    LaunchedEffect(owner, config.settings, config.activeProfileLikesCount) {
        owner.synchronizeEnvironment(
            settings = config.settings,
            activeProfileLikesCount = config.activeProfileLikesCount,
        )
    }
    LaunchedEffect(owner, config.availableSources) {
        owner.onSourceAvailabilityChanged()
    }

    CollectRouteEffects(owner.effects) { effect ->
        handleForYouRouteEffect(effect, callbacks, owner)
    }

    ForYouScreen(
        state = state,
        likedPostIds = config.likedPostIds,
        pixivUgoiraClient = pixivUgoiraClient,
        resolveUnknownAnimatedDurations = config.resolveUnknownAnimatedDurations,
        onToggleLike = callbacks.onToggleLike,
        onAction = owner::onAction,
        displayTagFor = owner::displayTagFor,
        creatorBrowsingSources = config.creatorBrowsingSources,
        onRequestSaveToCodex = callbacks.onRequestSaveToCodex,
        onSaveToDevice = callbacks.onSaveToDevice,
        onOpenCreatorProfile = callbacks.onOpenCreatorProfile,
        onOpenLegacyCreatorProfile = callbacks.onOpenLegacyCreatorProfile,
        onAddIncludeTerm = callbacks.onAddIncludeTerm,
        onAddExcludeTerm = callbacks.onAddExcludeTerm,
        onRemoveIncludeTerm = callbacks.onRemoveIncludeTerm,
        onRemoveExcludeTerm = callbacks.onRemoveExcludeTerm,
        onFavoriteTagLongPress = callbacks.onFavoriteTagLongPress,
        onGoToSearch = callbacks.onGoToSearch,
    )
}

private suspend fun handleForYouRouteEffect(
    effect: ForYouEffect,
    callbacks: ForYouRouteCallbacks,
    owner: ForYouViewModel,
) {
    when (effect) {
        is ForYouEffect.OpenViewer -> callbacks.onOpenViewer(effect)
        ForYouEffect.NavigateToSearch -> callbacks.onNavigateToSearch()
        is ForYouEffect.ShowMessage -> callbacks.onShowMessage(effect.message)
        is ForYouEffect.SeedHidden -> {
            if (callbacks.onSeedHidden(effect.profileId, effect.entries)) {
                owner.onAction(ForYouAction.UndoSeedBlacklist(effect.profileId, effect.entries))
            }
        }
        is ForYouEffect.BlacklistSeed,
        is ForYouEffect.ChangeProfile,
        is ForYouEffect.ChangeSort,
        is ForYouEffect.ChangeSource,
        is ForYouEffect.LoadNextPage,
        is ForYouEffect.RefreshFeed,
        is ForYouEffect.UndoSeedBlacklist,
        -> Unit
    }
}

private const val FOR_YOU_ROUTE_OWNER_KEY = "for-you-route-owner"
