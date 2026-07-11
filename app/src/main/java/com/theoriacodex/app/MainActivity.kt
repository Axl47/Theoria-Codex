package com.theoriacodex.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.theoriacodex.app.ui.TheoriaApp

class MainActivity : ComponentActivity() {
    private var incomingUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        incomingUri = intent?.incomingPayloadUri()
        setContent {
            TheoriaApp(
                incomingUri = incomingUri,
                onIncomingUriConsumed = {
                    incomingUri = null
                    intent?.clearConsumedIncomingPayload()
                },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingUri = intent.incomingPayloadUri()
    }
}

internal fun Intent.incomingPayloadUri(): Uri? {
    data?.let { return it }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let { return it }
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { return it }
    }
    clipData?.let { clip ->
        for (index in 0 until clip.itemCount) {
            clip.getItemAt(index).uri?.let { return it }
        }
    }
    return null
}

internal fun Intent.clearConsumedIncomingPayload() {
    data = null
    removeExtra(Intent.EXTRA_STREAM)
    clipData = null
}
