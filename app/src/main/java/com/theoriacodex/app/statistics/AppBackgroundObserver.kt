package com.theoriacodex.app.statistics

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.theoriacodex.app.backup.AutoBackupWorkflow
import com.theoriacodex.app.di.TheoriaAppContainer
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class AppBackgroundObserver(
    private val usage: AppUsageTracker,
    private val flushPendingWrites: suspend () -> Unit,
    private val autoBackup: AutoBackupWorkflow?,
    private val scope: CoroutineScope,
) : DefaultLifecycleObserver {
    override fun onStart(owner: LifecycleOwner) = usage.onForeground()

    override fun onStop(owner: LifecycleOwner) {
        usage.onBackground()
        scope.launch {
            runCatchingPreservingCancellation {
                usage.awaitIdle()
                flushPendingWrites()
            }
            autoBackup?.onAppBackgrounded()
        }
    }
}

internal fun TheoriaAppContainer.observeAppBackground(processLifecycle: Lifecycle) {
    processLifecycle.addObserver(
        AppBackgroundObserver(features.appUsageTracker, data::flushPendingWrites, workflows.autoBackup, applicationScope),
    )
}
