package com.theoriacodex.app.lift

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalViewConfiguration
import com.theoriacodex.app.ui.navigation.LiftController
import com.theoriacodex.app.ui.navigation.LiftLike
import com.theoriacodex.app.ui.navigation.LiftRelease
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.Query
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.job

/** What dropping a lifted post on a target does. */
sealed interface LiftAction {
    data class Save(val codexId: String, val codexName: String) : LiftAction
    data object NewCodex : LiftAction
    data object ToggleLike : LiftAction
    data object Similar : LiftAction
}

/** A post picked up from a card: where the card was, where the finger picked it up, and what the card lent. */
@Immutable
data class LiftedPost(val post: Post, val cardBounds: Rect, val origin: Offset, val query: Query?, val like: LiftLike?)

/** A lifted post dropped on a target, for the overlay to act on. */
data class LiftDrop(val lift: LiftedPost, val action: LiftAction)

/** Where a lift is, from the overlay's point of view. */
sealed interface LiftPhase {
    data object Idle : LiftPhase

    data class Lifted(val lift: LiftedPost) : LiftPhase

    /** Released at [at]: the copy settles into the target of [action], or back onto its card when it is null. */
    data class Settling(val lift: LiftedPost, val at: Offset, val action: LiftAction?) : LiftPhase
}

/**
 * The shell's [LiftController]: holds the one lift in progress for the overlay to draw. Positions are window
 * coordinates. The overlay tells it which target lies under a position ([targetAt]), collects [drops] to act
 * on them, and reports [settled] when a released copy has landed. Only the overlay reads [pointer] and
 * [hoverPointer], so a moving finger recomposes nothing else. A finger that has not moved past touch slop from
 * where it picked the card up is not aiming at a target: it hovers nothing, and releasing it opens the post menu.
 */
@Stable
class LiftSession(private val touchSlopPx: Float) : LiftController {
    var phase: LiftPhase by mutableStateOf(LiftPhase.Idle)
        private set

    var pointer: Offset by mutableStateOf(Offset.Zero)
        private set

    override val lifting: Boolean get() = phase is LiftPhase.Lifted

    /** The Similar lookup a drop started, shown as pending until it returns; [cancel] stops it. */
    var pending: Job? by mutableStateOf(null)
        private set

    /** [pointer] once it is past touch slop of the pick-up point, else null: the position that may hover a target. */
    val hoverPointer: Offset?
        get() = pointer.takeIf { (phase as? LiftPhase.Lifted)?.lift?.let { lift -> movedPastSlop(lift, it) } == true }

    /** The target under a window position, or null; the overlay points it at the targets it shows. */
    var targetAt: (Offset) -> LiftAction? = { null }

    private val dropChannel = Channel<LiftDrop>(Channel.UNLIMITED)

    /** Every drop on a target, once each. */
    val drops: Flow<LiftDrop> = dropChannel.receiveAsFlow()

    override fun start(post: Post, cardBounds: Rect, pointer: Offset, query: Query?, like: LiftLike?): Boolean {
        if (phase is LiftPhase.Lifted) return false
        // Until the overlay shows this lift's targets, nothing is under the finger.
        targetAt = { null }
        this.pointer = pointer
        phase = LiftPhase.Lifted(LiftedPost(post, cardBounds, pointer, query, like))
        return true
    }

    override fun move(pointer: Offset) {
        if (phase is LiftPhase.Lifted) this.pointer = pointer
    }

    override fun end(pointer: Offset): LiftRelease {
        val lift = (phase as? LiftPhase.Lifted)?.lift ?: return LiftRelease.Cancelled
        this.pointer = pointer
        if (!movedPastSlop(lift, pointer)) {
            phase = LiftPhase.Idle
            return LiftRelease.OpenMenu
        }
        val action = targetAt(pointer)
        phase = LiftPhase.Settling(lift, pointer, action)
        if (action == null) return LiftRelease.Cancelled
        dropChannel.trySend(LiftDrop(lift, action))
        return LiftRelease.Acted
    }

    override fun cancel() {
        phase = LiftPhase.Idle
        pending?.cancel()
    }

    /** Runs [block] as the lift's pending work: the overlay keeps its stage up until it returns or is cancelled. */
    suspend fun <T> whilePending(block: suspend () -> T): T {
        val job = currentCoroutineContext().job
        pending = job
        try {
            return block()
        } finally {
            if (pending === job) pending = null
        }
    }

    private fun movedPastSlop(lift: LiftedPost, at: Offset) = (at - lift.origin).getDistance() > touchSlopPx

    /** The overlay finished landing [settling]; a lift started since is left alone. */
    fun settled(settling: LiftPhase.Settling) {
        if (phase === settling) phase = LiftPhase.Idle
    }
}

/** One [LiftSession] for the shell, treating the platform's touch slop as "the finger never moved". */
@Composable
fun rememberLiftSession(): LiftSession {
    val touchSlop = LocalViewConfiguration.current.touchSlop
    return remember(touchSlop) { LiftSession(touchSlop) }
}
