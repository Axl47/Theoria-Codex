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

class TriageDeckCallbacks(
    val onOpenPost: (posts: List<Post>, index: Int) -> Unit,
    val onOpenSavePicker: (Post) -> Unit,
    val onNewCodex: (Post) -> Unit,
    val onClose: () -> Unit,
)

class TriageCardSupport(
    val environment: FeedCardEnvironment,
    val resolvePostById: (suspend (PostId) -> Post?)?,
    val recoverPostMedia: (suspend (Post, ImageRef) -> Post?)?,
)

@Composable
fun TriageDeckScreen(
    deck: TriageDeck,
    query: Query?,
    support: TriageCardSupport,
    callbacks: TriageDeckCallbacks,
    modifier: Modifier = Modifier,
    showTopBar: Boolean = true,
    cardFooter: (@Composable (Post) -> Unit)? = null,
    endContent: (@Composable () -> Unit)? = null,
) {
    val state by deck.state.collectAsStateWithLifecycle()
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (showTopBar) TriageTopBar(state, callbacks.onClose, deck::undo)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.current != null -> TriageDeckArea(state, deck, query, support, callbacks, cardFooter)
                state.finished -> if (endContent != null) endContent() else TriageEnd(callbacks.onClose)
                state.needsRetry -> TriageRetry(deck::retryPage)
                else -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
            TriageConfirmation(state.notice)
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

@Composable
private fun TriageRetry(onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().testTag(TRIAGE_RETRY_TAG),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Couldn't load more posts", style = MaterialTheme.typography.titleMedium)
        Button(onRetry) { Text("Retry") }
    }
}

internal const val TRIAGE_CLOSE_TAG = "triage-close"
internal const val TRIAGE_PROGRESS_TAG = "triage-progress"
internal const val TRIAGE_UNDO_TAG = "triage-undo"
internal const val TRIAGE_END_TAG = "triage-end"
internal const val TRIAGE_RETRY_TAG = "triage-retry"
