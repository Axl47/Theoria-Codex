package com.theoriacodex.app.ui.routes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.theoriacodex.app.media.MediaDurationCoordinator
import com.theoriacodex.app.media.MediaDurationKey
import com.theoriacodex.app.media.MediaDurationRouteViewModel
import com.theoriacodex.app.media.MediaDurationState
import com.theoriacodex.domain.model.Post

internal data class MediaDurationRouteBinding(
    val owner: MediaDurationRouteViewModel,
    val states: Map<MediaDurationKey, MediaDurationState>,
)

@Composable
internal fun rememberMediaDurationRouteBinding(
    coordinator: MediaDurationCoordinator,
    routeName: String,
    ownerKey: String,
    contentIdentity: String,
    posts: List<Post>,
    resolveInBackground: Boolean,
): MediaDurationRouteBinding {
    val owner = viewModel<MediaDurationRouteViewModel>(
        key = ownerKey,
        factory = MediaDurationRouteViewModel.factory(coordinator, routeName),
    )
    val states by owner.states.collectAsStateWithLifecycle()
    SideEffect {
        owner.synchronize(contentIdentity, posts, resolveInBackground)
    }
    return MediaDurationRouteBinding(owner, states)
}
