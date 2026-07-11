package com.theoriacodex.app.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.theoriacodex.app.di.TheoriaAppContainerOwner

/**
 * Resolves the application-owned graph without constructing dependencies in composition.
 */
@Composable
internal fun rememberTheoriaAppContainerOwner(appContext: Context): TheoriaAppContainerOwner {
    return remember(appContext) {
        appContext as? TheoriaAppContainerOwner
            ?: error("TheoriaApp requires a TheoriaAppContainerOwner as its application context")
    }
}
