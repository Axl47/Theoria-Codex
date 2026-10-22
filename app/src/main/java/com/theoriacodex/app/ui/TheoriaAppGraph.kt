package com.theoriacodex.app.ui

import android.net.Uri
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.theoriacodex.app.appshell.AppShellViewModel
import com.theoriacodex.app.appshell.ViewerSessionRetentionViewModel
import com.theoriacodex.data.storage.ApplicationDataState
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import kotlinx.coroutines.launch
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

@Composable
fun TheoriaApp(
    incomingUri: Uri? = null,
    onIncomingUriConsumed: () -> Unit = {},
) {
    val appContext = LocalContext.current.applicationContext
    val viewerSessionOwner = viewModel<ViewerSessionRetentionViewModel>()
    val appShellOwner = viewModel<AppShellViewModel>()
    val containerOwner = rememberTheoriaAppContainerOwner(appContext)
    val containerState by containerOwner.appContainerState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(containerOwner) { containerOwner.startAppContainer() }
    when (val current = containerState) {
        is ApplicationDataState.Ready -> TheoriaAppContent(
            appContainer = current.value,
            viewerSessionOwner = viewerSessionOwner,
            appShellOwner = appShellOwner,
            incomingUri = incomingUri,
            onIncomingUriConsumed = onIncomingUriConsumed,
        )
        is ApplicationDataState.Failed -> AppContainerStartupSurface(
            failed = true,
            onRetry = {
                scope.launch {
                    runCatchingPreservingCancellation { containerOwner.retryAppContainer() }
                }
            },
        )
        ApplicationDataState.Loading,
        ApplicationDataState.NotStarted,
        -> AppContainerStartupSurface(failed = false, onRetry = {})
    }
}
