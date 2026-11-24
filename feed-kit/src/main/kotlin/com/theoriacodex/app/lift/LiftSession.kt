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

sealed interface LiftAction {
    data class Save(val codexId: String, val codexName: String) : LiftAction
    data object NewCodex : LiftAction

    data object OpenPicker : LiftAction
    data object ToggleLike : LiftAction
    data object Similar : LiftAction
}

@Immutable
data class LiftedPost(val post: Post, val cardBounds: Rect, val origin: Offset, val query: Query?, val like: LiftLike?)

data class LiftDrop(val lift: LiftedPost, val action: LiftAction)

sealed interface LiftPhase {
    data object Idle : LiftPhase

    data class Lifted(val lift: LiftedPost) : LiftPhase

    data class Settling(val lift: LiftedPost, val at: Offset, val action: LiftAction?) : LiftPhase
}

@Stable
class LiftSession(private val touchSlopPx: Float) : LiftController {
    var phase: LiftPhase by mutableStateOf(LiftPhase.Idle)
        private set

    var pointer: Offset by mutableStateOf(Offset.Zero)
        private set

    override val lifting: Boolean get() = phase is LiftPhase.Lifted

    var pending: Job? by mutableStateOf(null)
        private set

    val hoverPointer: Offset?
        get() = pointer.takeIf { (phase as? LiftPhase.Lifted)?.lift?.let { lift -> movedPastSlop(lift, it) } == true }

    var targetAt: (Offset) -> LiftAction? = { null }

    private val dropChannel = Channel<LiftDrop>(Channel.UNLIMITED)

    val drops: Flow<LiftDrop> = dropChannel.receiveAsFlow()

    override fun start(post: Post, cardBounds: Rect, pointer: Offset, query: Query?, like: LiftLike?): Boolean {
        if (phase is LiftPhase.Lifted) return false
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

    fun settled(settling: LiftPhase.Settling) {
        if (phase === settling) phase = LiftPhase.Idle
    }
}

@Composable
fun rememberLiftSession(): LiftSession {
    val touchSlop = LocalViewConfiguration.current.touchSlop
    return remember(touchSlop) { LiftSession(touchSlop) }
}
