package com.theoriacodex.app.recommend

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theoriacodex.app.codex.ChosenCodex
import com.theoriacodex.app.related.RelatedShelfBinding
import com.theoriacodex.app.search.StatusRow
import com.theoriacodex.app.triage.TriageCardSupport
import com.theoriacodex.app.ui.components.FeedDurations
import com.theoriacodex.app.ui.components.FeedPostActionSheet
import com.theoriacodex.app.ui.components.ForgetRetainedFeedsEffect
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey

/** What the route keeps for the screen: the feed's saved choices and what its cards need. */
class ForYouScreenConfig(
    val sortMode: SortMode,
    val animatedOnly: Boolean,
    /** The grid shows the level's posts instead of the deck. */
    val grid: Boolean,
    val likedPostIds: Set<PostId>,
    val creatorBrowsingSources: Set<SourceKey>,
    val durations: FeedDurations,
    val support: TriageCardSupport,
    /** Whether a tag trail's search is already a saved search. */
    val isSearchSaved: (Query) -> Boolean = { false },
) {
    /** Sort or Animated only is off its default. */
    val filtered: Boolean get() = sortMode != SortMode.NEWEST || animatedOnly
}

/** What the For You screen asks of the app. */
class ForYouDeckCallbacks(
    /** Makes a profile the active one; the deck follows the settings. */
    val onSelectProfile: (profileId: String) -> Unit,
    val onSelectSource: (SourceKey?) -> Unit,
    val onSelectSort: (SortMode) -> Unit,
    val onAnimatedOnlyChange: (Boolean) -> Unit,
    /** Puts Sort and Animated only back to their defaults in one change. */
    val onResetFilters: () -> Unit,
    val onGridChange: (Boolean) -> Unit,
    /** Opens the Viewer on [post] of [level]'s feed, which the Viewer keeps following. */
    val onOpenPost: (level: ForYouLevel, post: PostId, scrollOffsetHint: Int) -> Unit,
    /** Asks which Codex to save [post] to, for the deck to save it there. */
    val onPickCodex: (post: Post, onPick: (ChosenCodex) -> Unit) -> Unit,
    val onRequestSaveToCodex: (Post) -> Unit,
    /** Saves a tag trail's search as a saved search. */
    val onSaveSearch: (Query) -> Unit,
    val onToggleLike: (Post) -> Unit,
    val onOpenSourceAccounts: () -> Unit,
    val onGoToSearch: () -> Unit,
)

/**
 * For You: the top level of [owner]'s stack as a swipe deck, or as a grid over the same posts. Above it sit the bar
 * (profile, source, Undo, the deck and grid toggle, filters) and the interest chips, which crumbs replace on a trail.
 * System Back leaves a trail one level at a time.
 */
@Composable
fun ForYouDeckScreen(
    owner: ForYouDeckViewModel,
    config: ForYouScreenConfig,
    related: RelatedShelfBinding,
    callbacks: ForYouDeckCallbacks,
    modifier: Modifier = Modifier,
) {
    val state by owner.state.collectAsStateWithLifecycle()
    val levels by owner.levels.collectAsStateWithLifecycle()
    val level = levels.last()
    val onTrail = levels.size > 1
    var showFilters by remember { mutableStateOf(false) }
    var actionPost by remember { mutableStateOf<Post?>(null) }
    // Each level on the stack keeps its own grid position; a level that left it is gone for good.
    ForgetRetainedFeedsEffect(LEVEL_ID_PREFIX, levels.mapTo(mutableSetOf(), ForYouLevel::id))
    BackHandler(enabled = onTrail) { owner.popTo(levels.lastIndex - 1) }

    // The shell's scaffold already insets its content for the system bars.
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ForYouBar(state, level, config, callbacks, onRefresh = owner::rebuild, onShowFilters = { showFilters = true })
        if (onTrail) {
            val query = level.query
            ForYouCrumbs(
                levels, onCrumb = owner::popTo, saved = query != null && config.isSearchSaved(query),
                onSaveSearch = query?.let { { callbacks.onSaveSearch(it) } },
            )
        } else {
            ForYouChips(state.chips, state.only, owner::selectChip)
            if (state.failures.isNotEmpty()) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    StatusRow(state.failures, onRetrySource = owner::retrySource, onOpenAccounts = callbacks.onOpenSourceAccounts)
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // Each level keeps its own deck and grid position.
            key(level.id, config.grid) {
                ForYouBody(owner, state, level, config, related, callbacks) { actionPost = it }
            }
        }
    }

    actionPost?.let { post ->
        FeedPostActionSheet(
            post, { actionPost = null }, callbacks.onGoToSearch, callbacks.onToggleLike,
            onSaveToCodex = callbacks.onRequestSaveToCodex,
            onShowRelated = { related.controller.show(post) },
        )
    }
    if (showFilters) ForYouFilterSheet(config, callbacks, onDismiss = { showFilters = false })
}
