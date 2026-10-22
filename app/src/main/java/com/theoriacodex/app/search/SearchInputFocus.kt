package com.theoriacodex.app.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

internal class SearchInputFocus(
    val rootModifier: Modifier,
    private val dismiss: (Boolean) -> Unit,
) {
    fun clear(force: Boolean = false) {
        dismiss(force)
    }
}

/** Clear the Compose focus and keyboard together when leaving or submitting Search. */
@Composable
internal fun rememberSearchInputFocus(): SearchInputFocus {
    val manager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    return remember(manager, keyboard) {
        SearchInputFocus(Modifier) { force ->
            manager.clearFocus(force)
            keyboard?.hide()
        }
    }
}
