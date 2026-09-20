package com.theoriacodex.app.search

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.platform.LocalFocusManager

internal class SearchInputFocus(
    val rootModifier: Modifier,
    private val dismiss: (Boolean) -> Unit,
) {
    fun clear(force: Boolean = false) {
        dismiss(force)
    }
}

/** Old Android immediately refocuses the first editor when its native Compose owner loses focus. */
@Composable
internal fun rememberSearchInputFocus(): SearchInputFocus {
    val manager = LocalFocusManager.current
    val page = remember { FocusRequester() }
    return remember(manager, page) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            // Keep native focus on the page without creating an accessibility control or fake button.
            SearchInputFocus(Modifier.focusRequester(page).focusTarget()) { page.requestFocus() }
        } else {
            SearchInputFocus(Modifier) { force -> manager.clearFocus(force) }
        }
    }
}
