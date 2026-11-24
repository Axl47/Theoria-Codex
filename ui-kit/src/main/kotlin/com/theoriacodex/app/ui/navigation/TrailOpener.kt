package com.theoriacodex.app.ui.navigation

import androidx.compose.runtime.staticCompositionLocalOf
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SourceKey

sealed interface TrailRequest {
    val from: Post

    data class Tag(val tag: String, val source: SourceKey, val context: Query?, override val from: Post) : TrailRequest

    data class Creator(val creator: CreatorProfile, override val from: Post) : TrailRequest

    data class Similar(override val from: Post) : TrailRequest
}

fun interface TrailOpener {
    suspend fun openTrail(request: TrailRequest): Boolean

    companion object {
        val None = TrailOpener { false }
    }
}

val LocalTrailOpener = staticCompositionLocalOf { TrailOpener.None }
