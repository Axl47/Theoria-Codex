package com.theoriacodex.app.settings.sites

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.theoriacodex.app.customsites.BrowserVerifiedSites

/**
 * SPIKE: shows [host]'s home page so the person can pass its browser challenge themselves. Nothing
 * is clicked or solved for them. "Done" keeps the WebView's cookies for that host and hands its
 * User-Agent back, since clearance cookies are usually bound to the agent that earned them.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun SiteVerificationPage(host: String, onAction: (CustomSitesAction) -> Unit) {
    BackHandler { onAction(CustomSitesAction.CancelVerification) }
    val context = LocalContext.current
    val webView = remember(host) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
            CookieManager.getInstance().setAcceptCookie(true)
            loadUrl("https://$host/")
        }
    }
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Verify $host", style = MaterialTheme.typography.titleMedium)
            Text(
                "Complete the site's check below, waiting until it asks you to click. Tap Done once the site loads.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = {
                    BrowserVerifiedSites.markVerified(host)
                    onAction(CustomSitesAction.FinishVerification(webView.settings.userAgentString))
                }) { Text("Done") }
                TextButton(onClick = { onAction(CustomSitesAction.CancelVerification) }) { Text("Cancel") }
                TextButton(onClick = { webView.reload() }) { Text("Reload") }
            }
        }
        AndroidView(factory = { webView }, modifier = Modifier.fillMaxWidth().weight(1f))
    }
}
