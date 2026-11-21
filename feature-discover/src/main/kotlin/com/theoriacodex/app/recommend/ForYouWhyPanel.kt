package com.theoriacodex.app.recommend

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theoriacodex.app.recommend.state.ForYouAction
import com.theoriacodex.app.recommend.state.ForYouUiState
import com.theoriacodex.app.source.displayName
import com.theoriacodex.app.ui.components.ActionMenuSheet
import com.theoriacodex.app.ui.components.SheetHeader
import com.theoriacodex.app.ui.undo.UndoPill
import com.theoriacodex.app.ui.undo.UndoRow
import com.theoriacodex.data.repository.ForYouBlacklistEntry
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.tags.normalizeMatchToken

/**
 * What a why panel explains: a shelf ([sectionId], [pinned] when the user pinned it) or the drilled-in feed's
 * seed, by its [title] and the [seed] tags it searches for.
 */
data class ForYouWhyTarget(
    val title: String,
    val seed: Map<SourceKey, List<String>>,
    val sectionId: String?,
    val pinned: Boolean = false,
) {
    /**
     * What the drilled-in feed does once this panel closes over it: a hidden interest no longer exists, so it
     * returns to the shelves; a hidden mix reshuffles. Null when nothing it shows was hidden, or on the shelves,
     * which update as soon as a tag is struck.
     */
    fun afterClose(homeVisible: Boolean, hidden: List<ForYouBlacklistEntry>): ForYouAction? = when {
        homeVisible || !hidden.hidesShelf(seed, pinned) -> null
        sectionId != null -> ForYouAction.ShowHome
        else -> ForYouAction.Refresh(shuffle = true)
    }
}

/** The why panel for [section], which the shelf's Why button opens. */
fun ForYouSection.whyTarget() = ForYouWhyTarget(title, feed.seed, id, pinned)

/**
 * Hosts For You's [content] with the why panel for [target] open over it. Closing the panel lets a drilled-in
 * feed catch up with what was hidden, once the hides written from it have landed; Undo for a hide that is still
 * live then shows on For You.
 */
@Composable
internal fun ForYouWhyHost(
    target: ForYouWhyTarget?,
    state: ForYouUiState,
    onAction: (ForYouAction) -> Unit,
    onClose: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        content()
        if (target == null) UndoPill(ForYouWhyUndoAnchor, Modifier.align(Alignment.BottomCenter))
    }
    target ?: return
    ForYouWhyPanel(target, state.blacklistEntries, { source, tag, hide ->
        onAction(if (hide) ForYouAction.HideTag(source, tag) else ForYouAction.UnhideTag(source, tag))
    }) {
        onClose()
        onAction(ForYouAction.CloseWhy(target))
    }
}

/**
 * Each tag [target] searches for with how many of the profile's Likes carry it, and a button that hides it
 * for this profile or, on a hidden tag (struck through), unhides it. Undo for the last hide shows here while
 * the panel is open, and on For You after it closes.
 */
@Composable
private fun ForYouWhyPanel(
    target: ForYouWhyTarget,
    hidden: List<ForYouBlacklistEntry>,
    onToggle: (source: SourceKey, tag: String, hide: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val counts by LocalLikedTagCounts.current.collectAsStateWithLifecycle()
    ActionMenuSheet(onDismiss) {
        SheetHeader(target.title, "Close tags", onDismiss)
        target.seed.forEach { (source, tags) ->
            if (target.seed.size > 1) {
                Text(source.displayName(), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
            tags.forEach { tag ->
                val struck = hidden.hidesSeed(source, listOf(tag))
                WhyTagRow(tag, counts.counts[normalizeMatchToken(tag)] ?: 0, struck) { onToggle(source, tag, !struck) }
            }
        }
        UndoRow(ForYouWhyUndoAnchor, Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun WhyTagRow(tag: String, likes: Int, hidden: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics(mergeDescendants = true) {
            if (hidden) stateDescription = "Hidden"
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (likes > 0) "$tag · $likes" else tag,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            textDecoration = if (hidden) TextDecoration.LineThrough else null,
            color = if (hidden) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
        IconButton(onClick = onToggle) {
            if (hidden) Icon(Icons.Default.Visibility, "Unhide $tag") else Icon(Icons.Default.VisibilityOff, "Hide $tag")
        }
    }
}
