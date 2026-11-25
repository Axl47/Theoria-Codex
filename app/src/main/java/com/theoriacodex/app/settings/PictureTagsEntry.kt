package com.theoriacodex.app.settings

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theoriacodex.app.ui.components.ConfirmDialog
import com.theoriacodex.app.ui.components.SettingsGroup
import com.theoriacodex.app.ui.components.SettingsHeader
import com.theoriacodex.app.ui.components.SettingsPageColumn
import com.theoriacodex.app.vision.AppPictures
import com.theoriacodex.app.vision.ModelState
import com.theoriacodex.app.vision.VisualModelStore
import com.theoriacodex.data.repository.SettingDestinations
import com.theoriacodex.data.repository.VisionProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

@Composable
internal fun rememberPictureTagsEntry(pictures: AppPictures, scope: CoroutineScope): SettingsPicturesEntry {
    val state by pictures.model.states.collectAsStateWithLifecycle()
    val summary = when (val model = state) {
        ModelState.Absent -> "Off"
        is ModelState.Downloading -> "Downloading ${percent(model.bytes, model.total)}%"
        ModelState.Ready -> "On"
        is ModelState.Failed -> "Download failed"
    }
    return remember(pictures, summary) {
        SettingsPicturesEntry(summary) { onBack -> PictureTagsPage(pictures.model, pictures.progress, scope, onBack) }
    }
}

@Composable
internal fun PictureTagsPage(model: VisualModelStore, library: Flow<VisionProgress>, scope: CoroutineScope, onBack: () -> Unit) {
    val context = LocalContext.current
    val state by model.states.collectAsStateWithLifecycle()
    val progress by library.collectAsStateWithLifecycle(VisionProgress(0, 0, 0))
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val size = Formatter.formatShortFileSize(context, model.totalBytes)
    SettingsPageColumn {
        SettingsHeader(SettingDestinations.PictureTags.title, onBack, SETTINGS_PATH)
        SettingsGroup(null) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state != ModelState.Ready) Text("Tags pictures on this phone, for hidden tags, For You and save suggestions.")
                when (val download = state) {
                    ModelState.Absent -> Button(onClick = model::download) { Text("Download ($size)") }
                    is ModelState.Downloading -> {
                        LinearProgressIndicator(progress = { download.bytes.toFloat() / download.total }, Modifier.fillMaxWidth())
                        Text("${Formatter.formatShortFileSize(context, download.bytes)} of $size", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { scope.launch { model.delete() } }) { Text("Cancel") }
                    }
                    is ModelState.Failed -> {
                        Text("Download failed.", color = MaterialTheme.colorScheme.error)
                        Button(onClick = model::download) { Text("Try again") }
                    }
                    ModelState.Ready -> {
                        libraryProgress(progress)?.let { summary ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(summary.count)
                                summary.pending?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete") }
                    }
                }
            }
        }
        Text(
            "WD ViT Tagger v3 by SmilingWolf, Apache License 2.0",
            Modifier.padding(horizontal = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete picture tags?",
            text = "Frees $size and removes the Library's picture tags.",
            confirmLabel = "Delete",
            onConfirm = {
                confirmDelete = false
                scope.launch { model.delete() }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

internal data class LibraryProgress(val count: String, val pending: String?)

internal fun libraryProgress(progress: VisionProgress): LibraryProgress? {
    if (progress.total == 0) return null
    val count = progress.awaitingFrames
    val videos = if (count == 1) "1 video finishes from its frames" else "$count videos finish from their frames"
    val pending = when {
        progress.tagged < progress.total && count > 0 -> "The rest are tagged while charging on Wi-Fi, and $videos once the phone is also idle"
        progress.tagged < progress.total -> "The rest are tagged while charging on Wi-Fi"
        count > 0 -> "$videos while charging and idle on Wi-Fi"
        else -> null
    }
    return LibraryProgress("${progress.tagged} of ${progress.total} Library posts tagged", pending)
}

private fun percent(bytes: Long, total: Long): Long = if (total <= 0) 0 else bytes * 100 / total
