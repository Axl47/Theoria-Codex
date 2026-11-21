package com.theoriacodex.app.ui.navigation

import androidx.compose.runtime.staticCompositionLocalOf
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SourceKey

/** A fork from the post [from] into another Viewer level. */
sealed interface TrailRequest {
    val from: Post

    /** Posts tagged [tag] on [source]; [context] is the query of the level the fork starts from, if any. */
    data class Tag(val tag: String, val source: SourceKey, val context: Query?, override val from: Post) : TrailRequest

    /** [creator]'s uploads. */
    data class Creator(val creator: CreatorProfile, override val from: Post) : TrailRequest

    /** Posts similar to [from]. */
    data class Similar(override val from: Post) : TrailRequest
}

/** Opens Trails (forked Viewer levels) from the Viewer and feed grids. Read from [LocalTrailOpener]. */
fun interface TrailOpener {
    /**
     * Fetches the fork's first page, then opens it as a new Viewer level. False when the fork failed or was
     * empty and nothing opened, so the caller can say so in place.
     */
    suspend fun openTrail(request: TrailRequest): Boolean

    companion object {
        val None = TrailOpener { false }
    }
}

val LocalTrailOpener = staticCompositionLocalOf { TrailOpener.None }
