package com.theoriacodex.app.settings.sites

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.customsites.SiteCookieBridgeOwner
import com.theoriacodex.app.ui.components.AnnouncedText
import com.theoriacodex.app.ui.components.BrowserPage
import com.theoriacodex.app.ui.components.SettingsHeader
import com.theoriacodex.app.ui.components.rememberBrowserState

/**
 * Shows [SiteVerification.host]'s home page so the person can pass its browser challenge themselves.
 * Nothing is clicked or solved for them. Each finished page load reports the browser's cookies and
 * User-Agent (clearance cookies are usually bound to the agent that earned them), the app checks
 * whether its own requests now get through, and the page closes itself once they do after a refusal.
 * Done asks for that check at once. The browser itself, with its missing-WebView and failed-load
 * states, is the shared [BrowserPage].
 */
@Composable
internal fun SiteVerificationPage(verification: SiteVerification, siteName: String, onAction: (CustomSitesAction) -> Unit) {
    val host = verification.host
    val cancel = { onAction(CustomSitesAction.CancelVerification) }
    BackHandler(onBack = cancel)
    val bridge = (LocalContext.current.applicationContext as SiteCookieBridgeOwner).siteCookieBridge
    val currentOnAction by rememberUpdatedState(onAction)
    val browser = rememberBrowserState("https://$host/") { userAgent ->
        currentOnAction(CustomSitesAction.VerificationPageLoaded(userAgent, bridge.cookiesFor("https://$host/")))
    }
    BrowserPage(
        browser,
        onClose = cancel,
        header = {
            Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
                SettingsHeader("Verify", onBack = cancel, parents = SITES_PATH + siteName) {
                    IconButton(onClick = browser::reload, enabled = browser.available) { Icon(Icons.Default.Refresh, "Reload") }
                }
            }
        },
        footer = {
            VerificationStatusBar(verification, browserReady = browser.available) {
                browser.userAgent?.let { onAction(CustomSitesAction.ConfirmVerification(it)) }
            }
        },
    )
}

/** The instruction, the last answer or "Checking…", above Done. */
@Composable
private fun VerificationStatusBar(verification: SiteVerification, browserReady: Boolean, onDone: () -> Unit) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (verification.checking) {
            Progress("Checking…")
        } else {
            AnnouncedText(verification.message ?: VERIFICATION_INSTRUCTION, error = verification.messageIsError)
        }
        Button(onClick = onDone, enabled = browserReady && !verification.checking) { Text("Done") }
    }
}
