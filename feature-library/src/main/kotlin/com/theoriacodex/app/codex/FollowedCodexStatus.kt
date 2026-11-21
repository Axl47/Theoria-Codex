package com.theoriacodex.app.codex

import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.Composable
import com.theoriacodex.app.source.displayName
import com.theoriacodex.app.ui.components.FeedView
import com.theoriacodex.data.repository.FollowedCreator
import com.theoriacodex.domain.model.Post

@Composable
fun FollowedCodexHeader(
    owner: FollowedCodexViewModel,
    follows: List<FollowedCreator>,
    feed: FeedView,
    posts: List<Post>,
    gridState: LazyStaggeredGridState,
    onBack: () -> Unit,
    onManage: () -> Unit,
) {
    LiveCodexHeader("Followed", FOLLOWED_CODEX_ID, owner,
        follows.associate { it.membershipId to "${it.creator.displayName} · ${it.creator.source.displayName()}" },
        feed, posts, gridState, onBack, "Authors", onManage)
}
