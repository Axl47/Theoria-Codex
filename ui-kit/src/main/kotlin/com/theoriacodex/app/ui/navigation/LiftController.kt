package com.theoriacodex.app.ui.navigation

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.Query

/** What a lift's release did, so the card knows whether to open its post menu. */
enum class LiftRelease {
    /** Dropped on a target, which acted. */
    Acted,

    /** Released within touch slop of the pick-up point: the card opens its post menu as a long-press did. */
    OpenMenu,

    /** Released elsewhere: nothing happens. */
    Cancelled,
}

/**
 * Lifts a feed card's post so it can be dragged onto collect targets drawn above the navigation host. A card
 * reports its long-press drag here, with positions in window coordinates. Read from [LocalLiftController].
 */
interface LiftController {
    /**
     * Starts lifting [post] from a card at [cardBounds] with the finger at [pointer]. [query] is the search the
     * card belongs to, for save ranking. False means no lift started, and the card keeps its long-click.
     */
    fun start(post: Post, cardBounds: Rect, pointer: Offset, query: Query? = null): Boolean

    fun move(pointer: Offset)

    fun end(pointer: Offset): LiftRelease

    /** The gesture was interrupted or the card left composition. */
    fun cancel()

    companion object {
        /** Never lifts, so cards keep their long-click. */
        val None: LiftController = object : LiftController {
            override fun start(post: Post, cardBounds: Rect, pointer: Offset, query: Query?) = false
            override fun move(pointer: Offset) = Unit
            override fun end(pointer: Offset) = LiftRelease.OpenMenu
            override fun cancel() = Unit
        }
    }
}

val LocalLiftController = staticCompositionLocalOf { LiftController.None }
