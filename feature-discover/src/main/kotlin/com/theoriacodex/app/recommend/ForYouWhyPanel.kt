package com.theoriacodex.app.recommend

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theoriacodex.app.recommend.state.ForYouAction
import com.theoriacodex.app.recommend.state.ForYouUiState
import com.theoriacodex.app.source.displayName
import com.theoriacodex.app.ui.components.ActionMenuSheet
import com.theoriacodex.app.ui.components.SheetHeader
import com.theoriacodex.app.ui.undo.UndoLeaveEffect
import com.theoriacodex.app.ui.undo.UndoRow
import com.theoriacodex.data.repository.ForYouBlacklistEntry
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.tags.normalizeMatchToken

/**
 * What a why panel explains: a shelf ([sectionId]) or the drilled-in feed's seed, by its [title] and the
 * [seed] tags it searches for.
 */
data class ForYouWhyTarget(
    val title: String,
    val seed: Map<SourceKey, List<String>>,
    val sectionId: String?,
) {
    /**
     * What the drilled-in feed does once this panel closes over it: a hidden interest no longer exists, so it
     * returns to the shelves; a hidden mix reshuffles. Null when nothing it shows was hidden, or on the shelves,
     * which update as soon as a tag is struck.
     */
    fun afterClose(homeVisible: Boolean, hidden: List<ForYouBlacklistEntry>): ForYouAction? = when {
        homeVisible || !hidden.hidesAny(seed) -> null
        sectionId != null -> ForYouAction.ShowHome
        else -> ForYouAction.Refresh(shuffle = true)
    }
}

/** The why panel for [section], which a shelf's title opens. */
fun ForYouSection.whyTarget() = ForYouWhyTarget(title, feed.seed, id)

/** A pinned shelf the user named, whose title no longer lists its tags, so it shows a disclosure for them. */
val ForYouSection.named: Boolean get() = pinned && title != feed.seed.values.flatten().joinToString(" + ")

/** Hosts the open why panel over For You; closing it lets a drilled-in feed catch up with what was hidden. */
@Composable
internal fun ForYouWhyHost(
    target: ForYouWhyTarget?,
    state: ForYouUiState,
    onAction: (ForYouAction) -> Unit,
    onClose: () -> Unit,
) {
    target ?: return
    ForYouWhyPanel(target, state.blacklistEntries, { source, tag -> onAction(ForYouAction.HideTag(source, tag)) }) {
        onClose()
        target.afterClose(state.home.visible, state.blacklistEntries)?.let(onAction)
    }
}

/**
 * Each tag [target] searches for with how many of the profile's Likes carry it, and a button that hides it
 * for this profile (already hidden tags are struck through). Undo for the last hide shows here and expires
 * when the panel closes.
 */
@Composable
private fun ForYouWhyPanel(
    target: ForYouWhyTarget,
    hidden: List<ForYouBlacklistEntry>,
    onHide: (SourceKey, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val counts by LocalLikedTagCounts.current.collectAsStateWithLifecycle()
    UndoLeaveEffect(ForYouWhyUndoAnchor.scope)
    ActionMenuSheet(onDismiss) {
        SheetHeader(target.title, "Close tags", onDismiss)
        target.seed.forEach { (source, tags) ->
            if (target.seed.size > 1) {
                Text(source.displayName(), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
            tags.forEach { tag ->
                WhyTagRow(tag, counts.counts[normalizeMatchToken(tag)] ?: 0, hidden.hidesSeed(source, listOf(tag))) {
                    onHide(source, tag)
                }
            }
        }
        UndoRow(ForYouWhyUndoAnchor, Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun WhyTagRow(tag: String, likes: Int, hidden: Boolean, onHide: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (likes > 0) "$tag · $likes" else tag,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            textDecoration = if (hidden) TextDecoration.LineThrough else null,
            color = if (hidden) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
        IconButton(onClick = onHide, enabled = !hidden) { Icon(Icons.Default.VisibilityOff, "Hide $tag") }
    }
}
