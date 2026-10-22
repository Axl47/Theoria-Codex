package com.theoriacodex.app.viewer

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.theoriacodex.app.ui.findActivity
import kotlin.math.roundToInt

internal enum class ViewerLevelControl { Brightness, Volume }

internal data class ViewerLevelFeedback(
    val control: ViewerLevelControl,
    val fraction: Float,
    val label: String,
    val available: Boolean = true,
)

/** Owns real display/media levels for one Viewer session, independently of player lifetimes. */
internal class ViewerLevelControls(context: Context, private val window: Window?) {
    private val resolver = context.contentResolver
    private val audio = context.getSystemService(AudioManager::class.java)
    private var enabled = false
    private var previousBrightness = -1f
    private var viewerBrightness: Float? = null
    private var gesture: LevelGesture? = null
    var feedback by mutableStateOf<ViewerLevelFeedback?>(null)
        private set
    var dragging by mutableStateOf(false)
        private set

    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        if (value) {
            previousBrightness = window?.attributes?.screenBrightness ?: -1f
            viewerBrightness?.let(::writeBrightness)
        } else {
            writeBrightness(previousBrightness)
            end()
            feedback = null
        }
    }

    fun begin(control: ViewerLevelControl) {
        if (!enabled) return
        runCatching {
            val fraction = when (control) {
                ViewerLevelControl.Brightness -> readBrightness()
                ViewerLevelControl.Volume -> readVolume().fraction
            }
            gesture = LevelGesture(control, fraction)
            dragging = true
            feedback = readFeedback(control)
        }.onFailure { unavailable(control) }
    }

    /** Incremental deltas preserve small volume movements and reverse immediately at either limit. */
    fun drag(delta: Float) {
        val current = gesture ?: return
        val fraction = (current.fraction + delta).coerceIn(0f, 1f)
        current.fraction = fraction
        runCatching {
            when (current.control) {
                ViewerLevelControl.Brightness -> {
                    check(window != null)
                    writeBrightness(fraction)
                    viewerBrightness = fraction
                }
                ViewerLevelControl.Volume -> {
                    check(audio != null && !audio.isVolumeFixed)
                    val min = audio.minimumMediaVolume()
                    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val target = (min + fraction * (max - min)).roundToInt().coerceIn(min, max)
                    if (target != audio.getStreamVolume(AudioManager.STREAM_MUSIC)) {
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                        // Respect safe-volume caps and device policies instead of displaying a fictitious level.
                        val actual = readVolume()
                        if (actual.label != target.toString()) current.fraction = actual.fraction
                    }
                }
            }
            feedback = readFeedback(current.control)
        }.onFailure { unavailable(current.control) }
    }

    fun end() { gesture = null; dragging = false }
    fun clearFeedback() { if (!dragging) feedback = null }

    private fun readBrightness(): Float {
        check(window != null)
        return window.attributes.screenBrightness.takeIf { it >= 0f }
            ?: (Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f).coerceIn(0f, 1f)
    }

    private fun writeBrightness(value: Float) {
        window?.let { it.attributes = it.attributes.apply { screenBrightness = value } }
    }

    private fun readVolume(): ViewerLevelFeedback {
        check(audio != null && !audio.isVolumeFixed)
        val min = audio.minimumMediaVolume()
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        check(max > min)
        val value = if (audio.isStreamMute(AudioManager.STREAM_MUSIC)) min else audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        return ViewerLevelFeedback(ViewerLevelControl.Volume, (value - min).toFloat() / (max - min), value.toString())
    }

    private fun readFeedback(control: ViewerLevelControl): ViewerLevelFeedback = when (control) {
        ViewerLevelControl.Brightness -> readBrightness().let { ViewerLevelFeedback(control, it, "${(it * 100).roundToInt()}%") }
        ViewerLevelControl.Volume -> readVolume()
    }

    private fun unavailable(control: ViewerLevelControl) {
        end()
        feedback = ViewerLevelFeedback(control, 0f, "Unavailable", available = false)
    }

    private data class LevelGesture(val control: ViewerLevelControl, var fraction: Float)
}

@Composable
internal fun rememberViewerLevelControls(enabled: Boolean): ViewerLevelControls {
    val context = LocalContext.current
    val window = context.findActivity()?.window
    val controls = remember(context, window) { ViewerLevelControls(context, window) }
    DisposableEffect(controls, enabled) {
        controls.setEnabled(enabled)
        onDispose { controls.setEnabled(false) }
    }
    return controls
}

private fun AudioManager.minimumMediaVolume(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) getStreamMinVolume(AudioManager.STREAM_MUSIC) else 0
