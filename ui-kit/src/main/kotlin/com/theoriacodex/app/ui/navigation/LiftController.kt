package com.theoriacodex.app.ui.navigation

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.Query

enum class LiftRelease {
    Acted,

    OpenMenu,

    Cancelled,
}

data class LiftLike(val liked: Boolean, val toggle: () -> Unit)

interface LiftController {
    fun start(post: Post, cardBounds: Rect, pointer: Offset, query: Query? = null, like: LiftLike? = null): Boolean

    val lifting: Boolean get() = false

    fun move(pointer: Offset)

    fun end(pointer: Offset): LiftRelease

    fun cancel()

    companion object {
        val None: LiftController = object : LiftController {
            override fun start(post: Post, cardBounds: Rect, pointer: Offset, query: Query?, like: LiftLike?) = false
            override fun move(pointer: Offset) = Unit
            override fun end(pointer: Offset) = LiftRelease.OpenMenu
            override fun cancel() = Unit
        }
    }
}

val LocalLiftController = staticCompositionLocalOf { LiftController.None }
