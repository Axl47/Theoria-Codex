package com.theoriacodex.app.codex

import androidx.compose.runtime.Composable
import com.theoriacodex.app.related.RelatedShelfBinding
import com.theoriacodex.app.ui.components.FeedCardEnvironment
import com.theoriacodex.app.ui.components.FeedRelatedPostsShelf
import com.theoriacodex.app.ui.components.RelatedFeedPresentation
import com.theoriacodex.domain.model.Post

/** The collection's related shelf, which the grid places below the post that opened it. */
@Composable
internal fun CodexRelatedShelf(
    shelf: RelatedShelfBinding,
    presentation: RelatedFeedPresentation,
    environment: FeedCardEnvironment,
    onOpenPostActions: (Post) -> Unit,
) {
    val controller = shelf.controller
    FeedRelatedPostsShelf(
        state = shelf.state,
        presentation = presentation,
        environment = environment,
        onOpenPost = shelf.onOpenPost,
        onLongPress = onOpenPostActions,
        onDismiss = { controller.clear() },
        onRetry = controller::retry,
        onPreviousPage = controller::showPreviousPage,
        onNextPage = controller::showNextPage,
    )
}
