package com.theoriacodex.app.settings

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

/** Builds Settings' Picture tags row and page from the app's [pictures]; deleting runs in [scope], so leaving finishes it. */
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
    val progress by library.collectAsStateWithLifecycle(VisionProgress(0, 0))
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val size = Formatter.formatShortFileSize(context, model.totalBytes)
    SettingsPageColumn {
        SettingsHeader(SettingDestinations.PictureTags.title, onBack, SETTINGS_PATH)
        SettingsGroup(null) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Tags pictures on this phone, for hidden tags, For You and save suggestions.")
                when (val download = state) {
                    ModelState.Absent -> Button(onClick = model::download) { Text("Download ($size)") }
                    is ModelState.Downloading -> {
                        LinearProgressIndicator(progress = { download.bytes.toFloat() / download.total }, Modifier.fillMaxWidth())
                        Text("${Formatter.formatShortFileSize(context, download.bytes)} of $size", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { scope.launch { model.delete() } }) { Text("Cancel") }
                    }
                    is ModelState.Failed -> {
                        Text(
                            if (download.mismatch) "The download didn't match the expected file." else "Download failed.",
                            color = MaterialTheme.colorScheme.error,
                        )
                        Button(onClick = model::download) { Text("Try again") }
                    }
                    ModelState.Ready -> {
                        libraryProgress(progress)?.let { Text(it) }
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
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete picture tags?") },
            text = { Text("Frees $size. The tags found so far are removed, and downloading again tags the Library from the start.") },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch { model.delete() }
                }) { Text("Delete") }
            },
        )
    }
}

/** "120 of 450 Library posts tagged", and when some are left, that the rest are tagged while charging; null for an empty Library. */
private fun libraryProgress(progress: VisionProgress): String? {
    if (progress.total == 0) return null
    val done = "${progress.analysed} of ${progress.total} Library posts tagged"
    return if (progress.analysed < progress.total) "$done. The rest are tagged while charging." else done
}

private fun percent(bytes: Long, total: Long): Long = if (total <= 0) 0 else bytes * 100 / total
