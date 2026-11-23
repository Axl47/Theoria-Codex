package com.theoriacodex.app.series

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.theoriacodex.app.source.LocalSourceHealth
import com.theoriacodex.app.source.VERIFY_IN_BROWSER_LABEL
import com.theoriacodex.app.source.retryWhenVerified
import com.theoriacodex.app.ui.components.EmptyState
import com.theoriacodex.app.ui.components.ErrorState
import com.theoriacodex.domain.model.SourceKey

/**
 * A source's failed load, where a Series screen shows one: [message] with Retry or, while [source] needs browser
 * verification, a line saying so with the action that opens the app's browser check. Once that need clears, by a
 * check passed here or anywhere else, the load runs again by itself. Without [onRetry], when trying again cannot help,
 * [message] shows alone. [compact] is the one-line form that sits among other rows; otherwise it is the full-width one.
 */
@Composable
internal fun SourceLoadFailure(
    message: String,
    source: SourceKey?,
    sourceName: String,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val health = LocalSourceHealth.current
    val challenged = onRetry != null && retryWhenVerified(listOfNotNull(source)) { onRetry() }.isNotEmpty()
    val text = if (challenged) "$sourceName needs browser verification" else message
    val label = if (challenged) VERIFY_IN_BROWSER_LABEL else RETRY
    val action = if (challenged && source != null) { { health.verify(source) } } else onRetry
    when {
        compact -> FailureRow(action, modifier, text, label)
        action == null -> EmptyState(text, modifier)
        else -> ErrorState(text, action, modifier, label)
    }
}

private const val RETRY = "Retry"
