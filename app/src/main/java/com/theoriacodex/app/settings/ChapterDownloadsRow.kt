package com.theoriacodex.app.settings

import android.text.format.Formatter
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.theoriacodex.app.ui.components.SettingsRow
import com.theoriacodex.data.repository.SeriesDownloads
import kotlinx.coroutines.launch

/**
 * The space downloaded chapters take, measured off the main thread each time the row shows, and Delete all, which
 * asks first: the chapters then need a connection again.
 */
@Composable
internal fun ChapterDownloadsRow(downloads: SeriesDownloads) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var measured by remember { mutableIntStateOf(0) }
    val bytes by produceState<Long?>(null, measured) { value = downloads.sizeBytes() }
    var confirming by remember { mutableStateOf(false) }
    SettingsRow(
        "Downloaded chapters",
        bytes?.let { Formatter.formatShortFileSize(context, it) },
        Icons.Default.Download,
        trailing = { TextButton(onClick = { confirming = true }, enabled = (bytes ?: 0) > 0) { Text("Delete all") } },
    )
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Delete all downloaded chapters?") },
            text = { Text("Reading them will need a connection again.") },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        scope.launch {
                            downloads.deleteAll()
                            measured++
                        }
                    },
                ) { Text("Delete") }
            },
        )
    }
}
