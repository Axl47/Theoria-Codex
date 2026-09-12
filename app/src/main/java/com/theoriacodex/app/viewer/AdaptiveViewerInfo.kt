package com.theoriacodex.app.viewer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.theoriacodex.app.ui.useViewerInfoSidePanel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AdaptiveViewerInfo(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val widthDp = LocalWindowInfo.current.containerSize.width / LocalDensity.current.density
    if (useViewerInfoSidePanel(widthDp)) {
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Row(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f).fillMaxHeight().clickable(onClick = onDismiss))
                Surface(modifier = Modifier.width(400.dp).fillMaxHeight()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        TextButton(onClick = onDismiss) { Text("Close info") }
                        Box(modifier = Modifier.weight(1f)) { content() }
                    }
                }
            }
        }
    } else {
        ModalBottomSheet(onDismissRequest = onDismiss, dragHandle = null) { content() }
    }
}
