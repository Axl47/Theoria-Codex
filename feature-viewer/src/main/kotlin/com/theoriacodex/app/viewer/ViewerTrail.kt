package com.theoriacodex.app.viewer

import androidx.compose.runtime.Immutable
import com.theoriacodex.app.ui.navigation.TrailRequest
import com.theoriacodex.domain.model.Query

data class ViewerCrumb(val label: String, val trailKey: String? = null)

@Immutable
class ViewerTrail(val crumbs: List<ViewerCrumb>, val levelQuery: Query?, val onCrumb: (Int) -> Unit) {
    val levelTrailKey: String? get() = crumbs.lastOrNull()?.trailKey

    companion object {
        val None = ViewerTrail(emptyList(), null) {}
    }
}

fun TrailRequest.trailKey(): String = when (this) {
    is TrailRequest.Tag -> "tag:${source.id}:$tag"
    is TrailRequest.Creator -> "creator:${creator.source.id}:${creator.profileId ?: creator.displayName}"
    is TrailRequest.Similar -> "similar:${from.id.source.id}:${from.id.sourcePostId}"
}
