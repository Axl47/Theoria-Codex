package com.theoriacodex.app.triage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.draw.alpha
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
import com.theoriacodex.app.codex.ChosenCodex
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

@Composable
internal fun TriageDeckArea(
    state: TriageDeckState,
    deck: TriageDeck,
    query: Query?,
    support: TriageCardSupport,
    callbacks: TriageDeckCallbacks,
    cardFooter: (@Composable (Post) -> Unit)?,
) {
    val post = checkNotNull(state.current)
    val suggestions = rememberTriageSuggestions(listOfNotNull(post, state.next), query)
    val targets = rememberTriageTargets(post, suggestions[post.id], query, deck)
    val drag = remember(post.id) { TriageDrag() }
    val saveState = remember(post.id) { CollectTargetsState<SaveTarget>() }
    val hideState = remember(post.id) { CollectTargetsState<String>() }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val onDecide by rememberUpdatedState { choice: TriageChoice, card: PostId ->
        haptics.performHapticFeedback(TheoriaHaptics.ToggleOn)
        deck.decide(choice, card)
    }
    DisposableEffect(drag) { onDispose(drag::cancelRelease) }
    BoxWithConstraints(Modifier.fillMaxSize().testTag(TRIAGE_DECK_TAG)) {
        val density = LocalDensity.current
        val thresholds = with(density) { SwipeThresholds(maxWidth.toPx() * COMMIT_FRACTION, FLING_SPEED.toPx(), CUE_DISTANCE.toPx()) }
        val ratioFloor = maxWidth / (maxHeight - CAPTION_ALLOWANCE - if (cardFooter != null) TRIAGE_CARD_FOOTER_HEIGHT else 0.dp)
        val area = with(density) { Size(maxWidth.toPx(), maxHeight.toPx()) }
        val release by rememberUpdatedState { velocity: Velocity? ->
            val released = ReleasedOn(saveState.hovered?.payload, hideState.hovered?.payload ?: targets.hide.firstOrNull()?.payload)
            drag.releaseIn(scope) {
                val choose: suspend (SwipeDirection) -> TriageChoice? = { released.choice(it, post, suggestions, deck.removes) }
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
                    customActions = triageActions(targets, deck.removes, saveElsewhere) { choice -> onDecide(choice, post.id) }
                },
        ) {
            TriageCards(state, drag, support, thresholds, ratioFloor, cardFooter) {
                val (posts, index) = state.viewerLaunch()
                callbacks.onOpenPost(posts, index)
            }
            TriageDragOverlays(drag, targets, saveState, hideState, thresholds, deck.removes)
        }
    }
}

private class ReleasedOn(val save: SaveTarget?, val hide: String?) {
    suspend fun choice(direction: SwipeDirection, post: Post, suggestions: TriageSuggestions, removes: Boolean): TriageChoice? {
        val saveTarget = if (direction != SwipeDirection.Up) null else when (save) {
            SaveTarget.New, SaveTarget.More -> null
            is SaveTarget.Codex -> save.suggestion
            null -> (suggestions[post.id] ?: suggestions.await(post.id))?.targets?.firstOrNull()
        }
        return choiceFor(direction, saveTarget, hide, removes)
    }
}

private fun TriageDeckCallbacks.saveElsewhere(post: Post, target: SaveTarget?) =
    if (target == SaveTarget.New) onNewCodex(post) else onOpenSavePicker(post)

internal class TriageTargets(val save: List<CollectTarget<SaveTarget>>, val hide: List<CollectTarget<String>>)

@Composable
private fun rememberTriageTargets(
    post: Post,
    suggestions: SaveSuggestions?,
    query: Query?,
    deck: TriageDeck,
): TriageTargets {
    val hidden by deck.hiddenTags.collectAsStateWithLifecycle(emptySet())
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

@Composable
internal fun TriageCards(
    deck: TriageDeckState,
    drag: TriageDrag,
    support: TriageCardSupport,
    thresholds: SwipeThresholds,
    ratioFloor: Float,
    footer: (@Composable (Post) -> Unit)? = null,
    onOpen: () -> Unit,
) {
    val current = checkNotNull(deck.current)
    deck.next?.takeIf { drag.moving }?.let { next ->
        val peek = Modifier.graphicsLayer {
            val reveal = (max(abs(drag.offset.x), abs(drag.offset.y)) / thresholds.commitPx).coerceIn(0f, 1f)
            val scale = PEEK_SCALE + (1f - PEEK_SCALE) * reveal
            scaleX = scale
            scaleY = scale
            alpha = reveal
        }
        TriageCard(next, support, ratioFloor, footer, onClick = {}, likeEnabled = false, modifier = peek)
    }
    key(current.id) {
        val dragged = Modifier.graphicsLayer {
            translationX = drag.offset.x
            translationY = drag.offset.y
            rotationZ = drag.offset.x / size.width * MAX_TILT_DEGREES
        }
        TriageCard(current, support, ratioFloor, footer, onClick = onOpen, likeEnabled = true, modifier = dragged)
    }
}

@Composable
private fun TriageCard(
    post: Post,
    support: TriageCardSupport,
    ratioFloor: Float,
    footer: (@Composable (Post) -> Unit)?,
    onClick: () -> Unit,
    likeEnabled: Boolean,
    modifier: Modifier,
) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp).then(modifier),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FeedPostCard(
            post = post,
            environment = support.environment,
            onClick = onClick,
            onLongPress = null,
            likeEnabled = likeEnabled,
            mediaAspectRatio = max(previewAspectRatio(post), ratioFloor),
            resolvePostById = support.resolvePostById,
            recoverPostMedia = support.recoverPostMedia,
        )
        if (footer != null) {
            Box(Modifier.fillMaxWidth().height(TRIAGE_CARD_FOOTER_HEIGHT).testTag(TRIAGE_CARD_FOOTER_TAG)) { footer(post) }
        }
    }
}

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
        CustomAccessibilityAction("Save to ${target.name}") { onDecide(TriageChoice.Save(ChosenCodex.Existing(target.codexId, target.name))); true }
    },
    CustomAccessibilityAction("New Codex") { onSaveElsewhere(SaveTarget.New); true },
    CustomAccessibilityAction("Choose Codex") { onSaveElsewhere(SaveTarget.More); true },
    targets.hide.firstOrNull()?.payload?.let { tag ->
        CustomAccessibilityAction("Hide ${tagLabel(tag)}") { onDecide(TriageChoice.Hide(tag)); true }
    },
)

@Composable
internal fun TriageDragOverlays(
    drag: TriageDrag,
    targets: TriageTargets,
    saveState: CollectTargetsState<SaveTarget>,
    hideState: CollectTargetsState<String>,
    thresholds: SwipeThresholds,
    removes: Boolean,
) {
    Box(Modifier.fillMaxSize()) {
        val offset = drag.offset
        val hints = edgeHints(offset, thresholds)
        if (hints.sideways > 0f) {
            val (icon, label) = when {
                offset.x > 0f && removes -> Icons.Filled.Check to "Keep"
                offset.x > 0f -> Icons.Filled.Favorite to "Like"
                removes -> Icons.Outlined.RemoveCircleOutline to "Remove"
                else -> Icons.Filled.Close to "Skip"
            }
            val armed = hints.armed == SwipeDirection.Left || hints.armed == SwipeDirection.Right
            TriageEdgeCue(icon, label, if (offset.x > 0f) Alignment.CenterEnd else Alignment.CenterStart, hints.sideways, armed)
        }
        if (hints.vertical > 0f) VerticalEdgeHint(drag, targets, saveState, hideState, hints)
    }
}

@Composable
private fun BoxScope.VerticalEdgeHint(
    drag: TriageDrag,
    targets: TriageTargets,
    saveState: CollectTargetsState<SaveTarget>,
    hideState: CollectTargetsState<String>,
    hints: EdgeHints,
) {
    val up = drag.offset.y < 0f
    val fade = Modifier.alpha(hints.vertical)
    when {
        up && targets.save.isEmpty() ->
            TriageEdgeCue(Icons.Outlined.CollectionsBookmark, "Save", Alignment.TopCenter, hints.vertical, hints.armed == SwipeDirection.Up)
        !up && targets.hide.isEmpty() ->
            TriageEdgeCue(Icons.Filled.Close, "Skip", Alignment.BottomCenter, hints.vertical, hints.armed == SwipeDirection.Down)
        up -> CollectTargets(targets.save, drag.rowPointer, CollectTargetsLayout.EdgeRow(atTop = true), fade, saveState)
        else -> CollectTargets(targets.hide, drag.rowPointer, CollectTargetsLayout.EdgeRow(atTop = false), fade, hideState)
    }
}

internal class EdgeHints(val sideways: Float, val vertical: Float, val armed: SwipeDirection?)

internal fun edgeHints(offset: Offset, thresholds: SwipeThresholds): EdgeHints {
    val armed = swipeDirection(offset.x, offset.y, 0f, 0f, thresholds.commitPx, thresholds.flingPx)
    val armedSideways = armed == SwipeDirection.Left || armed == SwipeDirection.Right
    fun progress(distance: Float, otherArmed: Boolean): Float {
        val shown = (abs(distance) / thresholds.commitPx).coerceIn(0f, 1f)
        return if (otherArmed) shown * UNARMED_DIM else shown
    }
    val vertical = if (abs(offset.y) > thresholds.cuePx) progress(offset.y, armedSideways) else 0f
    return EdgeHints(progress(offset.x, armed != null && !armedSideways), vertical, armed)
}

internal const val TRIAGE_DECK_TAG = "triage-deck"
internal const val TRIAGE_CARD_FOOTER_TAG = "triage-card-footer"

val TRIAGE_CARD_FOOTER_HEIGHT: Dp = 72.dp

private const val COMMIT_FRACTION = 0.3f
private const val UNARMED_DIM = 0.4f
private val FLING_SPEED: Dp = 700.dp
private val CUE_DISTANCE: Dp = 48.dp

private val CAPTION_ALLOWANCE: Dp = 72.dp
private const val PEEK_SCALE = 0.94f
private const val MAX_TILT_DEGREES = 10f
