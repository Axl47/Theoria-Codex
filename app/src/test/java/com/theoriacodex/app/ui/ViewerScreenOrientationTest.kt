package com.theoriacodex.app.ui

import android.content.pm.ActivityInfo
import com.theoriacodex.domain.model.ViewerOrientation.LANDSCAPE
import com.theoriacodex.domain.model.ViewerOrientation.PORTRAIT
import org.junit.Assert.assertEquals
import org.junit.Test

class ViewerScreenOrientationTest {
    @Test fun `with nothing pinned the Viewer follows the system rotation setting, and a pin turns once then holds that exact rotation (ADR 0174)`() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER, viewerScreenOrientation(null, PORTRAIT))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE, viewerScreenOrientation(LANDSCAPE, PORTRAIT))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT, viewerScreenOrientation(PORTRAIT, LANDSCAPE))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LOCKED, viewerScreenOrientation(LANDSCAPE, LANDSCAPE))
    }
}
