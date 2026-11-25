package com.theoriacodex.app.viewer

import androidx.compose.runtime.Immutable
import com.theoriacodex.app.ui.navigation.TrailRequest
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SourceKey

data class CrumbTag(val value: String, val source: SourceKey)

data class ViewerCrumb(val label: String, val trailKey: String? = null, val tag: CrumbTag? = null)

data class TrailRemoval(val backTo: Int, val replay: List<String>)

@Immutable
class ViewerTrail(
    val crumbs: List<ViewerCrumb>,
    val levelQuery: Query?,
    val onCrumb: (Int) -> Unit,
    val onRemove: (Int) -> Unit = {},
    val replay: String? = null,
) {
    val levelTrailKey: String? get() = crumbs.lastOrNull()?.trailKey

    companion object {
        val None = ViewerTrail(emptyList(), null, {})
    }
}

fun List<ViewerCrumb>.removal(index: Int): TrailRemoval? {
    if (index <= 0 || getOrNull(index)?.tag == null) return null
    val replay = asSequence().drop(index + 1).map { it.tag?.value }.takeWhile { it != null }.filterNotNull().toList()
    return TrailRemoval(index - 1, replay)
}

fun TrailRequest.trailKey(): String = when (this) {
    is TrailRequest.Tag -> "tag:${source.id}:$tag"
    is TrailRequest.Creator -> "creator:${creator.source.id}:${creator.profileId ?: creator.displayName}"
    is TrailRequest.Similar -> "similar:${from.id.source.id}:${from.id.sourcePostId}"
}
