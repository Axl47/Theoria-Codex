package com.theoriacodex.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theoriacodex.app.search.OFFLINE_FEED_MESSAGE
import com.theoriacodex.app.search.OFFLINE_FEED_TITLE
import com.theoriacodex.app.ui.LocalNetworkOnline

@Composable
fun FeedFailureTile(
    message: String,
    onRetry: (() -> Unit)?,
    title: String? = null,
    actionLabel: String = "Retry",
    accountProblem: Boolean = false,
    onDismiss: (() -> Unit)? = null,
) {
    val online by LocalNetworkOnline.current.collectAsStateWithLifecycle()
    val offline = !online && onRetry != null && !accountProblem
    FeedErrorTile(
        message = if (offline) OFFLINE_FEED_MESSAGE else message,
        title = if (offline) OFFLINE_FEED_TITLE else title,
        actionLabel = actionLabel,
        onRetry = onRetry,
        onDismiss = onDismiss,
    )
}
