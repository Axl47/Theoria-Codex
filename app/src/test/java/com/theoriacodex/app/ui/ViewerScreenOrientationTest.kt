package com.theoriacodex.app.ui

import android.content.pm.ActivityInfo
import com.theoriacodex.domain.model.ViewerOrientation
import org.junit.Assert.assertEquals
import org.junit.Test

class ViewerScreenOrientationTest {
    @Test fun `with nothing pinned the Viewer follows the system rotation setting, and a pin holds one orientation (ADR 0174)`() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER, viewerScreenOrientation(null))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE, viewerScreenOrientation(ViewerOrientation.LANDSCAPE))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT, viewerScreenOrientation(ViewerOrientation.PORTRAIT))
    }
}
