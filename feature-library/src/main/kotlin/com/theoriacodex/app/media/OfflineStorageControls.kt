package com.theoriacodex.app.media

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.ui.components.ErrorState

/**
 * Settings' Storage sheet: what media takes on this device, and clearing the disposable cache. Offline
 * copies themselves are managed in the Library's offline sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageSheet(
    state: OfflineStorageUiState,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onClearDisposable: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 24.dp).testTag("storage-sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Storage", style = MaterialTheme.typography.titleLarge)
            if (state.refreshing || state.working) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            StorageBreakdown(state, onRefresh)
            DisposableCacheControls(state, onClearDisposable)
            state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun StorageBreakdown(state: OfflineStorageUiState, onRefresh: () -> Unit) {
    val cache = state.disposable
    when {
        cache != null -> MediaStorageChart(
            mediaStorageBreakdown(
                offlineBytes = state.offline.bytes,
                imageBytes = cache.imageBytes,
                videoBytes = cache.videoBytes,
                gifBytes = cache.gifBytes,
                animationBytes = cache.animationBytes,
                downloadBytes = 0L,
            ),
        )
        state.refreshing -> Text("Calculating media storage…", style = MaterialTheme.typography.bodyMedium)
        else -> ErrorState("Storage breakdown unavailable", onRetry = onRefresh)
    }
}

/** One line for the Settings row: offline copies and cache. */
@Composable
fun OfflineStorageSummary(state: OfflineStorageUiState) {
    val context = LocalContext.current
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
    Text("Offline ${size(state.offline.bytes)} · Cache ${size(state.disposable?.totalBytes ?: 0L)}")
}

/** Managed offline copies and cache; exported downloads live outside the app's storage. */
fun OfflineStorageUiState.totalBytes(): Long =
    offline.bytes + (disposable?.totalBytes ?: 0L)

@Composable
private fun DisposableCacheControls(state: OfflineStorageUiState, onClear: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Disposable cache", style = MaterialTheme.typography.titleMedium)
        state.disposable?.let { cache ->
            Text(Formatter.formatShortFileSize(LocalContext.current, cache.totalBytes))
        }
        TextButton(onClick = onClear, enabled = state.controlsEnabled) { Text("Clear disposable cache") }
    }
}
