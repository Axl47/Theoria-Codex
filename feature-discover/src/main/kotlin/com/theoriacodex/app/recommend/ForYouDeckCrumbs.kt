package com.theoriacodex.app.recommend

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.ui.components.TrailCrumb
import com.theoriacodex.app.ui.components.TrailCrumbArrow

internal data class ForYouCrumb(val id: String, val label: String)

@Composable
internal fun ForYouCrumbs(crumbs: List<ForYouCrumb>, onCrumb: (depth: Int) -> Unit, saved: Boolean, onSaveSearch: (() -> Unit)?) {
    val state = rememberLazyListState()
    LaunchedEffect(crumbs.size) { state.animateScrollToItem((crumbs.size - 2).coerceAtLeast(0)) }
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp).testTag(FOR_YOU_CRUMBS_TAG), verticalAlignment = Alignment.CenterVertically) {
        ForYouCrumb(crumbs.first(), 0, crumbs.lastIndex, onCrumb)
        LazyRow(Modifier.weight(1f), state = state, verticalAlignment = Alignment.CenterVertically) {
            itemsIndexed(crumbs.drop(1), key = { _, crumb -> crumb.id }) { index, crumb -> ForYouCrumb(crumb, index + 1, crumbs.lastIndex, onCrumb) }
        }
        if (onSaveSearch != null) {
            IconButton(onSaveSearch, Modifier.testTag(FOR_YOU_SAVE_SEARCH_TAG), enabled = !saved) {
                val tint = MaterialTheme.colorScheme.primary
                if (saved) Icon(Icons.Filled.Bookmark, "Saved", tint = tint) else Icon(Icons.Filled.BookmarkBorder, "Save search", tint = tint)
            }
        }
    }
}

@Composable
private fun ForYouCrumb(crumb: ForYouCrumb, depth: Int, last: Int, onCrumb: (depth: Int) -> Unit) {
    TrailCrumb(
        crumb.label,
        current = depth == last,
        color = MaterialTheme.colorScheme.onSurface,
        dimColor = MaterialTheme.colorScheme.onSurfaceVariant,
        arrow = if (depth > 0) TrailCrumbArrow.Before else TrailCrumbArrow.None,
        maxWidth = 160.dp,
        verticalPadding = 12.dp,
        testTag = "$FOR_YOU_CRUMB_TAG_PREFIX$depth",
    ) { onCrumb(depth) }
}

const val FOR_YOU_CRUMBS_TAG = "for-you-crumbs"
const val FOR_YOU_CRUMB_TAG_PREFIX = "for-you-crumb-"
const val FOR_YOU_SAVE_SEARCH_TAG = "for-you-save-search"
