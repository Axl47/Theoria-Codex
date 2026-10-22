package com.theoriacodex.app.codex

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner

internal val LocalSavedCodexSessionOwner = staticCompositionLocalOf<ViewModelStoreOwner?> { null }

/** Retains live Saved feeds across navigation, including their pagination and retry state. */
@Composable
internal fun SavedCodexSession(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalSavedCodexSessionOwner provides checkNotNull(LocalViewModelStoreOwner.current)) {
        content()
    }
}
