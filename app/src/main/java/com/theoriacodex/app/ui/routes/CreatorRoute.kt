package com.theoriacodex.app.ui.routes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.theoriacodex.app.creator.CreatorProfileCoordinator
import com.theoriacodex.app.creator.CreatorProfileScreen
import com.theoriacodex.app.creator.CreatorProfileViewModel
import com.theoriacodex.app.media.AnimatedDurationEnricher
import com.theoriacodex.app.creator.state.CreatorAction
import com.theoriacodex.app.creator.state.CreatorEffect
import com.theoriacodex.app.creator.state.CreatorUiState
import com.theoriacodex.app.viewer.PixivUgoiraClient
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SourceKey
import java.io.Closeable
import kotlinx.coroutines.flow.StateFlow

/** App-level inputs rendered by Creator or used to reconcile source capability. */
internal data class CreatorRouteConfig(
    val activeCreator: CreatorProfile?,
    val availableSources: Set<SourceKey>,
    val likedPostIds: Set<PostId>,
    val savedPostIds: Set<PostId>,
    val resolveUnknownAnimatedDurations: Boolean,
)

/** Platform and navigation effects that remain owned by the application shell. */
internal data class CreatorRouteCallbacks(
    val onOpenViewer: suspend (CreatorEffect.OpenViewer) -> Unit,
    val onNavigateBack: () -> Unit,
    val onToggleLike: (Post) -> Unit,
    val onRequestSaveToCodex: (Post) -> Unit,
    val onSaveToDevice: (Post) -> Unit,
    val onPostUrlCopied: (Post) -> Unit = {},
    val onOpenUrl: (String) -> Unit,
    val onAddIncludeTerm: (Post, SearchTerm) -> Boolean,
    val onAddExcludeTerm: (Post, SearchTerm) -> Boolean,
    val onRemoveIncludeTerm: (Post, SearchTerm) -> Unit,
    val onRemoveExcludeTerm: (Post, SearchTerm) -> Unit,
)

/** Non-owning access used by Viewer while the Creator back-stack entry owns the ViewModel. */
internal class CreatorRouteOwnerHandle(
    owner: CreatorProfileViewModel,
) : ObservableRouteOwnerHandle {
    private val ownerLease = owner.createRouteOwnerLease()

    /** Read-only state while the navigation-owned ViewModel remains alive. */
    val state: StateFlow<CreatorUiState>?
        get() = ownerLease.withOwner { activeOwner -> activeOwner.state }

    override fun invokeOnOwnerCleared(listener: () -> Unit): Closeable {
        return ownerLease.invokeOnClose(listener)
    }

    fun dispatch(action: CreatorAction): Boolean {
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

    fun currentState(): CreatorUiState? = ownerLease.withOwner { activeOwner -> activeOwner.state.value }
}

/** Navigation-scoped Creator owner plus its immutable rendering boundary. */
@Composable
internal fun CreatorRoute(
    coordinator: CreatorProfileCoordinator,
    animatedDurationEnricher: AnimatedDurationEnricher,
    pixivUgoiraClient: PixivUgoiraClient?,
    config: CreatorRouteConfig,
    callbacks: CreatorRouteCallbacks,
    onOwnerAvailable: (CreatorRouteOwnerHandle) -> Unit = {},
) {
    val owner = viewModel<CreatorProfileViewModel>(
        factory = CreatorProfileViewModel.factory(coordinator, animatedDurationEnricher),
    )
    val state by owner.state.collectAsStateWithLifecycle()
    val ownerHandle = remember(owner) { CreatorRouteOwnerHandle(owner) }
    val currentOnOwnerAvailable by rememberUpdatedState(onOwnerAvailable)

    SideEffect { currentOnOwnerAvailable(ownerHandle) }

    LaunchedEffect(owner, config.activeCreator) {
        config.activeCreator?.let { creator ->
            if (owner.state.value.creator != creator || owner.state.value.results.isEmpty()) {
                owner.onAction(CreatorAction.OpenCreator(creator))
            }
        }
    }
    LaunchedEffect(owner, config.availableSources) {
        owner.onSourceAvailabilityChanged()
    }

    CollectRouteEffects(owner.effects) { effect ->
        when (effect) {
            is CreatorEffect.OpenViewer -> callbacks.onOpenViewer(effect)
            CreatorEffect.NavigateBack -> callbacks.onNavigateBack()
            is CreatorEffect.LoadCreator,
            is CreatorEffect.LoadNextPage,
            is CreatorEffect.Refresh,
            -> Unit
        }
    }

    CreatorProfileScreen(
        state = state,
        likedPostIds = config.likedPostIds,
        savedPostIds = config.savedPostIds,
        pixivUgoiraClient = pixivUgoiraClient,
        resolveUnknownAnimatedDurations = config.resolveUnknownAnimatedDurations,
        onToggleLike = callbacks.onToggleLike,
        onAction = owner::onAction,
        onRequestSaveToCodex = callbacks.onRequestSaveToCodex,
        onSaveToDevice = callbacks.onSaveToDevice,
        onPostUrlCopied = callbacks.onPostUrlCopied,
        onOpenUrl = callbacks.onOpenUrl,
        onAddIncludeTerm = callbacks.onAddIncludeTerm,
        onAddExcludeTerm = callbacks.onAddExcludeTerm,
        onRemoveIncludeTerm = callbacks.onRemoveIncludeTerm,
        onRemoveExcludeTerm = callbacks.onRemoveExcludeTerm,
    )
}
