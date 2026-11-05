package com.theoriacodex.app.settings

import android.content.Context
import android.content.Intent
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.provider.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect

/** Sites can't verify a third-party app, so Android only sends their links here once the user allows it. */
@Composable
internal fun OpenLinksRow() {
    val context = LocalContext.current
    var summary by remember { mutableStateOf(context.openLinksSummary()) }
    // The choice is made in system settings, so re-read it each time the user comes back.
    LifecycleResumeEffect(context) {
        summary = context.openLinksSummary()
        onPauseOrDispose {}
    }
    SettingsRow("Open links in Theoria", summary, Icons.Default.Link, onClick = {
        context.startActivity(
            Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, "package:${context.packageName}".toUri()),
        )
    })
}

private fun Context.openLinksSummary(): String {
    val state = getSystemService(DomainVerificationManager::class.java)?.getDomainVerificationUserState(packageName)
    val hosts = state?.hostToStateMap.orEmpty()
    // Per-site choices survive Android's overall switch, but it overrides them while off.
    val allowed = if (state?.isLinkHandlingAllowed == true) hosts.values.count { it != DomainVerificationUserState.DOMAIN_STATE_NONE } else 0
    return openLinksSummary(allowed, hosts.size)
}

internal fun openLinksSummary(allowed: Int, total: Int): String = when (allowed) {
    0 -> "Off. Provider links open in your browser"
    total -> "On for all supported sites"
    else -> "On for $allowed of $total sites"
}
