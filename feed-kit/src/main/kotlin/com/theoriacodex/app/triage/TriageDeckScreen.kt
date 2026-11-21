package com.theoriacodex.app.triage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theoriacodex.app.ui.components.FeedCardEnvironment
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query

/** What a deck does beyond swiping: the app opens a post's Viewer, the save picker, and closes the deck. */
class TriageDeckCallbacks(
    val onOpenPost: (posts: List<Post>, index: Int) -> Unit,
    val onOpenSavePicker: (Post) -> Unit,
    val onClose: () -> Unit,
)

/** What the deck's cards need to render and resolve media, as the other feeds pass to their cards. */
class TriageCardSupport(
    val environment: FeedCardEnvironment,
    val resolvePostById: (suspend (PostId) -> Post?)?,
    val recoverPostMedia: (suspend (Post, ImageRef) -> Post?)?,
)

/**
 * The triage deck: one full-width feed card at a time under a bar with a close control, the position ("14 / 60")
 * and Undo. Drag the card right to like, left to skip (or remove, on a Codex), up to save, down to hide; [query] is
 * the origin feed's search, which ranks Codices and keeps its own tags out of what the deck offers to hide.
 */
@Composable
fun TriageDeckScreen(
    viewModel: TriageDeckViewModel,
    query: Query?,
    support: TriageCardSupport,
    callbacks: TriageDeckCallbacks,
) {
    val deck by viewModel.state.collectAsStateWithLifecycle()
    // The shell's scaffold already insets its content for the system bars.
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TriageTopBar(deck, callbacks.onClose, viewModel::undo)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                deck.current != null -> TriageDeckArea(deck, viewModel, query, support, callbacks)
                deck.finished -> TriageEnd(callbacks.onClose)
                else -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
            TriageConfirmation(deck.notice)
        }
    }
}

@Composable
private fun TriageTopBar(deck: TriageDeckState, onClose: () -> Unit, onUndo: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClose, Modifier.testTag(TRIAGE_CLOSE_TAG)) { Icon(Icons.Filled.Close, contentDescription = "Close") }
        Text(
            progressLabel(deck),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f).testTag(TRIAGE_PROGRESS_TAG),
        )
        IconButton(onUndo, Modifier.testTag(TRIAGE_UNDO_TAG), enabled = deck.last != null) {
            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
        }
    }
}

/** "14 / 60": the card on screen over the posts the deck has so far, with a plus while the feed has more. */
internal fun progressLabel(deck: TriageDeckState): String {
    val position = (deck.index + 1).coerceAtMost(deck.posts.size)
    return "$position / ${deck.posts.size}${if (deck.canLoadMore) "+" else ""}"
}

@Composable
private fun TriageEnd(onClose: () -> Unit) {
    Column(
        Modifier.fillMaxSize().testTag(TRIAGE_END_TAG),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No more posts", style = MaterialTheme.typography.titleMedium)
        Button(onClose) { Text("Done") }
    }
}

internal const val TRIAGE_CLOSE_TAG = "triage-close"
internal const val TRIAGE_PROGRESS_TAG = "triage-progress"
internal const val TRIAGE_UNDO_TAG = "triage-undo"
internal const val TRIAGE_END_TAG = "triage-end"
