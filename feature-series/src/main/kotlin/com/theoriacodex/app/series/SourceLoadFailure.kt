package com.theoriacodex.app.series

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.theoriacodex.app.source.LocalSourceHealth
import com.theoriacodex.app.source.VERIFY_IN_BROWSER_LABEL
import com.theoriacodex.app.ui.components.ErrorState
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.http.HostOutageKind

/**
 * A source's failed load, where a Series screen shows one: [message] with Retry or, while [source] needs browser
 * verification, a line saying so with the action that opens the app's browser check. Once that need clears, by a
 * check passed here or anywhere else, the load runs again by itself. [compact] is the one-line form that sits among
 * other rows; otherwise it is the full-width one.
 */
@Composable
internal fun SourceLoadFailure(
    message: String,
    source: SourceKey?,
    sourceName: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val health = LocalSourceHealth.current
    val challenged = source != null && health.issues[source] == HostOutageKind.CHALLENGE
    var sawChallenge by remember { mutableStateOf(false) }
    val retry by rememberUpdatedState(onRetry)
    LaunchedEffect(challenged) {
        if (challenged) sawChallenge = true else if (sawChallenge) retry()
    }
    val text = if (challenged) "$sourceName needs browser verification" else message
    val label = if (challenged) VERIFY_IN_BROWSER_LABEL else RETRY
    val action = if (challenged && source != null) { { health.verify(source) } } else onRetry
    if (compact) FailureRow(action, modifier, text, label) else ErrorState(text, action, modifier, label)
}

private const val RETRY = "Retry"
