package com.theoriacodex.app

import android.content.Intent
import android.net.Uri
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
        incomingUri = intent?.data
        setContent {
            TheoriaApp(
                incomingUri = incomingUri,
                onIncomingUriConsumed = { incomingUri = null },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingUri = intent.data
    }
}
