package com.theoriacodex.app.recommend

import com.theoriacodex.app.ui.components.hidesOnScroll
import com.theoriacodex.app.ui.components.ScrollChromeBox
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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

class ForYouScreenConfig(
    val sortMode: SortMode,
    val animatedOnly: Boolean,
    val grid: Boolean,
    val creatorBrowsingSources: Set<SourceKey>,
    val durations: FeedDurations,
    val support: TriageCardSupport,
    val isSearchSaved: (Query) -> Boolean = { false },
) {
    val filtered: Boolean get() = sortMode != SortMode.NEWEST || animatedOnly
}

class ForYouDeckCallbacks(
    val onSelectProfile: (profileId: String) -> Unit,
    val onSelectSource: (SourceKey?) -> Unit,
    val onSelectSort: (SortMode) -> Unit,
    val onAnimatedOnlyChange: (Boolean) -> Unit,
    val onResetFilters: () -> Unit,
    val onGridChange: (Boolean) -> Unit,
    val onOpenPost: (level: ForYouLevel, post: PostId, scrollOffsetHint: Int) -> Unit,
    val onPickCodex: (post: Post, onPick: (ChosenCodex) -> Unit) -> Unit,
    val onRequestSaveToCodex: (Post) -> Unit,
    val onSaveSearch: (Query) -> Unit,
    val onToggleLike: (Post) -> Unit,
    val onOpenSourceAccounts: () -> Unit,
    val onGoToSearch: () -> Unit,
)

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
    ForgetRetainedFeedsEffect(LEVEL_ID_PREFIX, levels.mapTo(mutableSetOf(), ForYouLevel::id))
    BackHandler(enabled = onTrail) { owner.popTo(levels.lastIndex - 1) }

    ScrollChromeBox(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), enabled = config.grid) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.hidesOnScroll()) {
                ForYouBar(state, level, config, callbacks, onRefresh = owner::rebuild, onShowFilters = { showFilters = true })
                AnimatedVisibility(onTrail, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    val query = level.query
                    ForYouCrumbs(
                        levels, onCrumb = owner::popTo, saved = query != null && config.isSearchSaved(query),
                        onSaveSearch = query?.let { { callbacks.onSaveSearch(it) } },
                    )
                }
                if (!onTrail) {
                    if (state.failures.isNotEmpty()) {
                        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                            StatusRow(state.failures, onRetrySource = owner::retrySource, onOpenAccounts = callbacks.onOpenSourceAccounts)
                        }
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                key(level.id.takeIf { config.grid }, config.grid) {
                    ForYouBody(owner, state, level, config, related, callbacks) { actionPost = it }
                }
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
    if (showFilters) {
        ForYouFilterSheet(
            config, callbacks, lanes = if (onTrail) emptyList() else state.lanes, onFocusLane = owner::focusLane,
            onReset = {
                callbacks.onResetFilters()
                owner.warmLanes()
            },
            onDismiss = { showFilters = false },
        )
    }
}
