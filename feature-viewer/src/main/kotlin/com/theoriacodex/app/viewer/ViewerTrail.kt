package com.theoriacodex.app.viewer

import androidx.compose.runtime.Immutable
import com.theoriacodex.app.tags.TagChipAction
import com.theoriacodex.app.ui.navigation.TrailRequest
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SourceKey

data class CrumbTag(val value: String, val source: SourceKey)

data class ViewerCrumb(val label: String, val trailKey: String? = null, val tag: CrumbTag? = null)

data class TrailRemoval(val backTo: Int, val replay: List<CrumbTag>)

@Immutable
class ViewerTrail(
    val crumbs: List<ViewerCrumb>,
    val levelQuery: Query?,
    val onCrumb: (Int) -> Unit,
    val onRemove: (Int) -> Unit = {},
    val replay: CrumbTag? = null,
    val onRestart: (CrumbTag) -> Unit = {},
) {
    val levelTrailKey: String? get() = crumbs.lastOrNull()?.trailKey

    fun tagActions(tag: CrumbTag, fork: () -> Unit): List<TagChipAction> {
        if (crumbs.size <= 1) return listOf(TagChipAction("Start a trail", fork))
        val step = crumbs.indexOfFirst { it.tag == tag }.takeIf { crumbs.removal(it) != null }
        val move = if (step == null) TagChipAction("Add to trail", fork) else TagChipAction("Remove from trail") { onRemove(step) }
        return listOf(move, TagChipAction("Start a new trail") { onRestart(tag) })
    }

    companion object {
        val None = ViewerTrail(emptyList(), null, {})
    }
}

fun List<ViewerCrumb>.removal(index: Int): TrailRemoval? {
    if (index <= 0 || getOrNull(index)?.tag == null) return null
    val replay = asSequence().drop(index + 1).map { it.tag }.takeWhile { it != null }.filterNotNull().toList()
    return TrailRemoval(index - 1, replay)
}

fun TrailRequest.trailKey(): String = when (this) {
    is TrailRequest.Tag -> "tag:${source.id}:$tag"
    is TrailRequest.Creator -> "creator:${creator.source.id}:${creator.profileId ?: creator.displayName}"
    is TrailRequest.Similar -> "similar:${from.id.source.id}:${from.id.sourcePostId}"
}
