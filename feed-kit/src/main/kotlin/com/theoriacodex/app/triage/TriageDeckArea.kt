package com.theoriacodex.app.triage

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theoriacodex.app.codex.SaveTarget
import com.theoriacodex.app.codex.SaveSuggestions
import com.theoriacodex.app.codex.saveMenuTargets
import com.theoriacodex.app.recommend.LocalTagAffinityInputs
import com.theoriacodex.app.search.previewAspectRatio
import com.theoriacodex.app.ui.components.CollectTarget
import com.theoriacodex.app.ui.components.CollectTargetVisual
import com.theoriacodex.app.ui.components.CollectTargets
import com.theoriacodex.app.ui.components.CollectTargetsLayout
import com.theoriacodex.app.ui.components.CollectTargetsState
import com.theoriacodex.app.ui.components.FeedPostCard
import com.theoriacodex.app.ui.motion.TheoriaHaptics
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
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
    val suggestions = rememberTriageSuggestions(listOfNotNull(post, deck.next), query)
    val targets = rememberTriageTargets(post, suggestions[post.id], query, viewModel)
    val drag = remember(post.id) { TriageDrag() }
    val saveState = remember(post.id) { CollectTargetsState<SaveTarget>() }
    val hideState = remember(post.id) { CollectTargetsState<String>() }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val onDecide by rememberUpdatedState { choice: TriageChoice, card: PostId ->
        haptics.performHapticFeedback(TheoriaHaptics.ToggleOn)
        viewModel.decide(choice, card)
    }
    // A release that outlives its card (a save waiting for targets, then Undo) must not decide for the next one.
    DisposableEffect(drag) { onDispose(drag::cancelRelease) }
    BoxWithConstraints(Modifier.fillMaxSize().testTag(TRIAGE_DECK_TAG)) {
        val density = LocalDensity.current
        val thresholds = with(density) { SwipeThresholds(maxWidth.toPx() * COMMIT_FRACTION, FLING_SPEED.toPx(), CUE_DISTANCE.toPx()) }
        val ratioFloor = maxWidth / (maxHeight - CAPTION_ALLOWANCE)
        val area = with(density) { Size(maxWidth.toPx(), maxHeight.toPx()) }
        // The gesture outlives recompositions that bring new targets, so it calls through the latest release. The targets
        // the finger let go on are read now, before the lifted pointer clears them.
        val release by rememberUpdatedState { velocity: Velocity? ->
            val released = ReleasedOn(saveState.hovered?.payload, hideState.hovered?.payload ?: targets.hide.firstOrNull()?.payload)
            drag.releaseIn(scope) {
                val choose: suspend (SwipeDirection) -> TriageChoice? = { released.choice(it, post, suggestions, viewModel.removes) }
                this.release(velocity, thresholds, area, choose, { choice -> onDecide(choice, post.id) }) {
                    callbacks.saveElsewhere(post, released.save)
                }
            }
        }
        var origin by remember { mutableStateOf(Offset.Zero) }
        Box(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned { origin = it.positionInWindow() }
                .triageSwipe(drag, { origin }) { velocity -> release(velocity) }
                .semantics {
                    val saveElsewhere = { target: SaveTarget -> callbacks.saveElsewhere(post, target) }
                    customActions = triageActions(targets, viewModel.removes, saveElsewhere) { choice -> onDecide(choice, post.id) }
                },
        ) {
            TriageCards(deck, drag, support, thresholds, ratioFloor) {
                val (posts, index) = deck.viewerLaunch()
                callbacks.onOpenPost(posts, index)
            }
            TriageDragOverlays(drag, targets, saveState, hideState, thresholds, viewModel.removes)
        }
    }
}

/** The save and hide targets a drag was let go on, if any; [hide] falls back to the first tag offered. */
private class ReleasedOn(val save: SaveTarget?, val hide: String?) {
    /**
     * What a release in [direction] chooses. A save swiped before the card's targets load waits for them rather than
     * reading "none"; let go on New or More, it names no Codex, so the card springs back for [saveElsewhere].
     */
    suspend fun choice(direction: SwipeDirection, post: Post, suggestions: TriageSuggestions, removes: Boolean): TriageChoice? {
        val saveTarget = if (direction != SwipeDirection.Up) null else when (save) {
            SaveTarget.New, SaveTarget.More -> null
            is SaveTarget.Codex -> save.suggestion
            null -> (suggestions[post.id] ?: suggestions.await(post.id))?.targets?.firstOrNull()
        }
        return choiceFor(direction, saveTarget, hide, removes)
    }
}

/** A save that names no Codex: New asks for a new one's name, anything else opens the picker. */
private fun TriageDeckCallbacks.saveElsewhere(post: Post, target: SaveTarget?) =
    if (target == SaveTarget.New) onNewCodex(post) else onOpenSavePicker(post)

/**
 * The edge targets for the current card: the shared save menu ([saveMenuTargets]) once its suggestions have loaded, even
 * when there are none, and tags to hide (least liked first).
 */
private class TriageTargets(val save: List<CollectTarget<SaveTarget>>, val hide: List<CollectTarget<String>>)

@Composable
private fun rememberTriageTargets(
    post: Post,
    suggestions: SaveSuggestions?,
    query: Query?,
    viewModel: TriageDeckViewModel,
): TriageTargets {
    val hidden by viewModel.hiddenTags.collectAsStateWithLifecycle(emptySet())
    val affinity by LocalTagAffinityInputs.current.collectAsStateWithLifecycle()
    val candidates = remember(post, affinity, hidden, query) {
        val skipped = query?.let { it.includeTags + it.excludeTags }.orEmpty() +
            hidden.filter { it.source == post.id.source }.map(HiddenTag::tag)
        hideCandidates(post, affinity, skipped)
    }
    return remember(suggestions, candidates) {
        TriageTargets(
            save = suggestions?.let { loaded -> saveMenuTargets(loaded.targets) { it } }.orEmpty(),
            hide = candidates.map { tag -> CollectTarget(tag, tagLabel(tag), CollectTargetVisual.Icon(Icons.Outlined.VisibilityOff), tag) },
        )
    }
}

/**
 * The card, with the next one behind it while the card is away from rest. The next card is the same feed card, so it
 * plays like any visible one for as long as it shows; at rest it is not composed, so it holds no player.
 */
@Composable
internal fun TriageCards(
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
            FeedPostCard(
                post = next,
                environment = support.environment,
                onClick = {},
                onLongPress = null,
                likeEnabled = false,
                mediaAspectRatio = max(previewAspectRatio(next), ratioFloor),
                resolvePostById = support.resolvePostById,
                recoverPostMedia = support.recoverPostMedia,
            )
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

/**
 * Lets TalkBack users decide without dragging; when the deck [removes], right is Keep and left Remove. New Codex and
 * Choose Codex do what dropping on New and More do, through [onSaveElsewhere].
 */
private fun triageActions(
    targets: TriageTargets,
    removes: Boolean,
    onSaveElsewhere: (SaveTarget) -> Unit,
    onDecide: (TriageChoice) -> Unit,
): List<CustomAccessibilityAction> = listOfNotNull(
    if (removes) CustomAccessibilityAction("Keep") { onDecide(TriageChoice.Keep); true }
    else CustomAccessibilityAction("Like") { onDecide(TriageChoice.Like); true },
    if (removes) CustomAccessibilityAction("Remove") { onDecide(TriageChoice.Remove); true }
    else CustomAccessibilityAction("Skip") { onDecide(TriageChoice.Skip); true },
    (targets.save.firstOrNull()?.payload as? SaveTarget.Codex)?.suggestion?.let { target ->
        CustomAccessibilityAction("Save to ${target.name}") { onDecide(TriageChoice.Save(target.codexId, target.name)); true }
    },
    CustomAccessibilityAction("New Codex") { onSaveElsewhere(SaveTarget.New); true },
    CustomAccessibilityAction("Choose Codex") { onSaveElsewhere(SaveTarget.More); true },
    targets.hide.firstOrNull()?.payload?.let { tag ->
        CustomAccessibilityAction("Hide ${tagLabel(tag)}") { onDecide(TriageChoice.Hide(tag)); true }
    },
)

/**
 * Edge targets for a vertical pull, and the like and skip (or, when the deck [removes], keep and remove) cues for a
 * horizontal one; none of it takes touches.
 */
@Composable
private fun TriageDragOverlays(
    drag: TriageDrag,
    targets: TriageTargets,
    saveState: CollectTargetsState<SaveTarget>,
    hideState: CollectTargetsState<String>,
    thresholds: SwipeThresholds,
    removes: Boolean,
) {
    Box(Modifier.fillMaxSize()) {
        val offset = drag.offset
        val pointer = drag.pointer
        val vertical = abs(offset.y) > abs(offset.x)
        val progress = (max(abs(offset.x), abs(offset.y)) / thresholds.commitPx).coerceIn(0f, 1f)
        when {
            !vertical -> TriageEdgeCue(
                when {
                    offset.x > 0f && removes -> Icons.Filled.Check
                    offset.x > 0f -> Icons.Filled.Favorite
                    removes -> Icons.Outlined.RemoveCircleOutline
                    else -> Icons.Filled.Close
                },
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
private const val PEEK_SCALE = 0.94f
private const val MAX_TILT_DEGREES = 10f
