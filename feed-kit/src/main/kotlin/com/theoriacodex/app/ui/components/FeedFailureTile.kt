package com.theoriacodex.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theoriacodex.app.search.OFFLINE_FEED_MESSAGE
import com.theoriacodex.app.search.OFFLINE_FEED_TITLE
import com.theoriacodex.app.ui.LocalNetworkOnline

/**
 * A feed's failure card, which says the device is offline instead of [message] while it is and a retry could help.
 * [retriesOnReconnect] is whether the feed retries by itself once the network returns; feeds that do not keep
 * [message] under the offline title rather than promise a retry that never comes. An [accountProblem] (an account or
 * verification failure) is never shown as offline, since reconnecting won't fix it.
 */
@Composable
fun FeedFailureTile(
    message: String,
    onRetry: (() -> Unit)?,
    title: String? = null,
    actionLabel: String = "Retry",
    accountProblem: Boolean = false,
    retriesOnReconnect: Boolean = true,
    onDismiss: (() -> Unit)? = null,
) {
    val online by LocalNetworkOnline.current.collectAsStateWithLifecycle()
    val offline = !online && onRetry != null && !accountProblem
    FeedErrorTile(
        message = if (offline && retriesOnReconnect) OFFLINE_FEED_MESSAGE else message,
        title = if (offline) OFFLINE_FEED_TITLE else title,
        actionLabel = actionLabel,
        onRetry = onRetry,
        onDismiss = onDismiss,
    )
}
