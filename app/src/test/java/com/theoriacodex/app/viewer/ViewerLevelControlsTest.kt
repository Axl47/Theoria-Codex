package com.theoriacodex.app.viewer

import android.app.Activity
import android.app.Application
import android.media.AudioManager
import android.provider.Settings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ViewerLevelControlsTest {
    private val activityController = Robolectric.buildActivity(Activity::class.java)
    private lateinit var activity: Activity
    private lateinit var controls: ViewerLevelControls
    private lateinit var audio: AudioManager

    @Before fun setup() {
        activity = activityController.setup().get()
        audio = activity.getSystemService(AudioManager::class.java)
        controls = ViewerLevelControls(activity, activity.window)
    }

    @After fun cleanup() {
        controls.setEnabled(false)
        activityController.pause().stop().destroy()
    }

    @Test fun `brightness starts at system level changes the display and restores automatic policy`() {
        Settings.System.putInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 102)
        Settings.System.putInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
        controls.setEnabled(true)
        controls.begin(ViewerLevelControl.Brightness)
        controls.drag(0.2f)
        assertEquals(0.6f, activity.window.attributes.screenBrightness, 0.001f)
        assertEquals("60%", controls.feedback?.label)
        assertEquals(102, Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS))
        controls.setEnabled(false)
        assertEquals(-1f, activity.window.attributes.screenBrightness, 0f)
        assertEquals(Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC,
            Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE))
        assertNull(controls.feedback)
        controls.setEnabled(true)
        assertEquals(0.6f, activity.window.attributes.screenBrightness, 0.001f)
    }

    @Test fun `brightness clamps reverses immediately and restores an existing override`() {
        activity.window.attributes = activity.window.attributes.apply { screenBrightness = 0.3f }
        controls.setEnabled(true)
        controls.begin(ViewerLevelControl.Brightness)
        controls.drag(2f)
        assertEquals(1f, activity.window.attributes.screenBrightness, 0f)
        controls.drag(-0.1f)
        assertEquals(0.9f, activity.window.attributes.screenBrightness, 0.001f)
        controls.drag(-2f)
        assertEquals(0f, activity.window.attributes.screenBrightness, 0f)
        controls.drag(0.1f)
        assertEquals(0.1f, activity.window.attributes.screenBrightness, 0.001f)
        controls.setEnabled(false)
        assertEquals(0.3f, activity.window.attributes.screenBrightness, 0f)
    }

    @Test @Config(sdk = [26, 35]) fun `volume adjusts the system media stream reaches mute and reads external changes`() {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0)
        controls.setEnabled(true)
        controls.begin(ViewerLevelControl.Volume)
        controls.drag(-2f)
        assertEquals(0, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertEquals("0", controls.feedback?.label)
        assertEquals(0f, controls.feedback!!.fraction, 0f)
        controls.end()
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0)
        controls.begin(ViewerLevelControl.Volume)
        assertEquals(max.toString(), controls.feedback?.label)
        controls.drag(-1f / max)
        assertEquals(max - 1, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        controls.setEnabled(false)
        assertEquals(max - 1, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    @Test fun `small volume movements accumulate and inactive or ended gestures cannot write`() {
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        controls.begin(ViewerLevelControl.Volume)
        controls.drag(1f)
        assertEquals(0, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        controls.setEnabled(true)
        controls.begin(ViewerLevelControl.Volume)
        repeat(100) { controls.drag(0.01f) }
        assertEquals(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        controls.end()
        controls.drag(-1f)
        assertEquals(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), audio.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    @Test fun `missing display reports unavailable and feedback cannot expire during a drag`() {
        val missingWindow = ViewerLevelControls(activity, null)
        missingWindow.setEnabled(true)
        missingWindow.begin(ViewerLevelControl.Brightness)
        assertFalse(missingWindow.feedback!!.available)
        assertFalse(missingWindow.dragging)
        controls.setEnabled(true)
        controls.begin(ViewerLevelControl.Volume)
        controls.clearFeedback()
        assertEquals(ViewerLevelControl.Volume, controls.feedback?.control)
        controls.end()
        controls.clearFeedback()
        assertNull(controls.feedback)
    }
}
