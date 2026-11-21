package com.theoriacodex.app.triage

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theoriacodex.app.codex.LocalQuickSave
import com.theoriacodex.app.codex.SaveSuggestion
import com.theoriacodex.app.codex.SaveSuggestions
import com.theoriacodex.app.media.previewMediaDeliveryPlan
import com.theoriacodex.app.recommend.LocalLikedTagCounts
import com.theoriacodex.app.search.FeedAsyncImage
import com.theoriacodex.app.search.buildFeedImageRequest
import com.theoriacodex.app.search.feedPreviewDecodeSize
import com.theoriacodex.app.search.previewAspectRatio
import com.theoriacodex.app.ui.components.CollectTarget
import com.theoriacodex.app.ui.components.CollectTargetVisual
import com.theoriacodex.app.ui.components.CollectTargets
import com.theoriacodex.app.ui.components.CollectTargetsLayout
import com.theoriacodex.app.ui.components.CollectTargetsState
import com.theoriacodex.app.ui.components.FeedPostCard
import com.theoriacodex.app.ui.motion.TheoriaHaptics
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.Query
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

/** The deck's card, its drag, and the edge targets a drag reveals. */
@Composable
internal fun TriageDeckArea(
    deck: TriageDeckState,
    viewModel: TriageDeckViewModel,
    query: Query?,
    support: TriageCardSupport,
    callbacks: TriageDeckCallbacks,
) {
    val post = checkNotNull(deck.current)
    val targets = rememberTriageTargets(post, query, viewModel)
    val drag = remember(post.id) { TriageDrag() }
    val saveState = remember(post.id) { CollectTargetsState<SaveSuggestion>() }
    val hideState = remember(post.id) { CollectTargetsState<String>() }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val onDecide by rememberUpdatedState { choice: TriageChoice ->
        haptics.performHapticFeedback(TheoriaHaptics.ToggleOn)
        viewModel.decide(choice)
    }
    BoxWithConstraints(Modifier.fillMaxSize().testTag(TRIAGE_DECK_TAG)) {
        val density = LocalDensity.current
        val thresholds = with(density) { SwipeThresholds(maxWidth.toPx() * COMMIT_FRACTION, FLING_SPEED.toPx(), CUE_DISTANCE.toPx()) }
        val ratioFloor = maxWidth / (maxHeight - CAPTION_ALLOWANCE)
        val area = with(density) { Size(maxWidth.toPx(), maxHeight.toPx()) }
        val choose: suspend (SwipeDirection) -> TriageChoice? = { direction ->
            choiceFor(
                direction,
                saveState.hovered?.payload ?: targets.save.firstOrNull()?.payload,
                hideState.hovered?.payload ?: targets.hide.firstOrNull()?.payload,
            )
        }
        // The gesture outlives recompositions that bring new targets, so it calls through the latest release.
        val release by rememberUpdatedState { velocity: Velocity? ->
            scope.launch {
                drag.release(velocity, thresholds, area, choose, onDecide) { callbacks.onOpenSavePicker(post) }
            }
            Unit
        }
        var origin by remember { mutableStateOf(Offset.Zero) }
        Box(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned { origin = it.positionInWindow() }
                .triageSwipe(drag, { origin }) { velocity -> release(velocity) }
                .semantics { customActions = triageActions(targets, onDecide) },
        ) {
            TriageCards(deck, drag, support, thresholds, ratioFloor) {
                callbacks.onOpenPost(deck.posts, deck.index)
            }
            TriageDragOverlays(drag, targets, saveState, hideState, thresholds)
        }
    }
}

/** The edge targets for the current card: Codices to save to (best first) and tags to hide (least liked first). */
private class TriageTargets(val save: List<CollectTarget<SaveSuggestion>>, val hide: List<CollectTarget<String>>)

@Composable
private fun rememberTriageTargets(post: Post, query: Query?, viewModel: TriageDeckViewModel): TriageTargets {
    val hidden by viewModel.hiddenTags.collectAsStateWithLifecycle(emptySet())
    val likedCounts by LocalLikedTagCounts.current.collectAsStateWithLifecycle()
    val quickSave = LocalQuickSave.current
    val suggestions by remember(post.id, query) { quickSave.suggestions(post, query, TRIAGE_SUGGESTIONS) }
        .collectAsStateWithLifecycle(SaveSuggestions.Empty)
    val candidates = remember(post, likedCounts, hidden, query) {
        val skipped = query?.let { it.includeTags + it.excludeTags }.orEmpty() +
            hidden.filter { it.source == post.id.source }.map(HiddenTag::tag)
        hideCandidates(post, likedCounts, skipped)
    }
    return remember(suggestions, candidates) {
        TriageTargets(
            save = suggestions.targets.map { target ->
                CollectTarget(target.codexId, target.name, CollectTargetVisual.Cover(target.cover, Icons.Outlined.CollectionsBookmark), target)
            },
            hide = candidates.map { tag -> CollectTarget(tag, tag, CollectTargetVisual.Icon(Icons.Outlined.VisibilityOff), tag) },
        )
    }
}

/**
 * The card, with the next one peeking behind it while the card is away from rest. The peek is a still: only the
 * card on top plays, and the next card's player starts when it becomes the top card.
 */
@Composable
private fun TriageCards(
    deck: TriageDeckState,
    drag: TriageDrag,
    support: TriageCardSupport,
    thresholds: SwipeThresholds,
    ratioFloor: Float,
    onOpen: () -> Unit,
) {
    val current = checkNotNull(deck.current)
    deck.next?.takeIf { drag.moving }?.let { next ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .graphicsLayer {
                    val reveal = (max(abs(drag.offset.x), abs(drag.offset.y)) / thresholds.commitPx).coerceIn(0f, 1f)
                    val scale = PEEK_SCALE + (1f - PEEK_SCALE) * reveal
                    scaleX = scale
                    scaleY = scale
                },
            contentAlignment = Alignment.Center,
        ) {
            TriagePeek(next, max(previewAspectRatio(next), ratioFloor))
        }
    }
    key(current.id) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .graphicsLayer {
                    translationX = drag.offset.x
                    translationY = drag.offset.y
                    rotationZ = drag.offset.x / size.width * MAX_TILT_DEGREES
                },
            contentAlignment = Alignment.Center,
        ) {
            FeedPostCard(
                post = current,
                environment = support.environment,
                onClick = onOpen,
                onLongPress = null,
                mediaAspectRatio = max(previewAspectRatio(current), ratioFloor),
                resolvePostById = support.resolvePostById,
                recoverPostMedia = support.recoverPostMedia,
            )
        }
    }
}

/** The next card's first preview image at the card's shape, requested as the card does; never animated. */
@Composable
private fun TriagePeek(post: Post, aspectRatio: Float) {
    val context = LocalContext.current
    val windowWidth = LocalWindowInfo.current.containerSize.width
    val request = remember(post.id, windowWidth) {
        previewMediaDeliveryPlan(post).candidates.firstOrNull()?.let { candidate ->
            buildFeedImageRequest(
                context, candidate.location, post.id.source,
                feedPreviewDecodeSize(screenWidthPx = windowWidth, aspectRatio = previewAspectRatio(post)),
            )
        }
    }
    FeedAsyncImage(
        request,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        isActive = false,
        modifier = Modifier.fillMaxWidth().aspectRatio(aspectRatio).clip(CardDefaults.elevatedShape),
    )
}

/** Lets TalkBack users decide without dragging. */
private fun triageActions(targets: TriageTargets, onDecide: (TriageChoice) -> Unit): List<CustomAccessibilityAction> = listOfNotNull(
    CustomAccessibilityAction("Like") { onDecide(TriageChoice.Like); true },
    CustomAccessibilityAction("Skip") { onDecide(TriageChoice.Skip); true },
    targets.save.firstOrNull()?.payload?.let { target ->
        CustomAccessibilityAction("Save to ${target.name}") { onDecide(TriageChoice.Save(target.codexId, target.name)); true }
    },
    targets.hide.firstOrNull()?.payload?.let { tag ->
        CustomAccessibilityAction("Hide $tag") { onDecide(TriageChoice.Hide(tag)); true }
    },
)

/** Edge targets for a vertical pull, and the like and skip cues for a horizontal one; none of it takes touches. */
@Composable
private fun TriageDragOverlays(
    drag: TriageDrag,
    targets: TriageTargets,
    saveState: CollectTargetsState<SaveSuggestion>,
    hideState: CollectTargetsState<String>,
    thresholds: SwipeThresholds,
) {
    Box(Modifier.fillMaxSize()) {
        val offset = drag.offset
        val pointer = drag.pointer
        val vertical = abs(offset.y) > abs(offset.x)
        val progress = (max(abs(offset.x), abs(offset.y)) / thresholds.commitPx).coerceIn(0f, 1f)
        when {
            !vertical -> TriageEdgeCue(
                if (offset.x > 0f) Icons.Filled.Favorite else Icons.Filled.Close,
                if (offset.x > 0f) Alignment.CenterEnd else Alignment.CenterStart,
                progress,
            )
            offset.y < -thresholds.cuePx && targets.save.isEmpty() ->
                TriageEdgeCue(Icons.Outlined.CollectionsBookmark, Alignment.TopCenter, progress)
            offset.y < -thresholds.cuePx ->
                CollectTargets(targets.save, pointer, CollectTargetsLayout.EdgeRow(atTop = true), state = saveState)
            offset.y > thresholds.cuePx ->
                CollectTargets(targets.hide, pointer, CollectTargetsLayout.EdgeRow(atTop = false), state = hideState)
        }
    }
}

internal const val TRIAGE_DECK_TAG = "triage-deck"

/** The card commits once dragged this far across the deck's width. */
private const val COMMIT_FRACTION = 0.3f
private val FLING_SPEED: Dp = 700.dp
private val CUE_DISTANCE: Dp = 48.dp

/** Room the card's caption takes below its media. */
private val CAPTION_ALLOWANCE: Dp = 72.dp
private const val TRIAGE_SUGGESTIONS = 3
private const val PEEK_SCALE = 0.94f
private const val MAX_TILT_DEGREES = 10f
