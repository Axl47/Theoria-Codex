package com.theoriacodex.app.source

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.theoriacodex.sources.hitomi.HitomiProtocol
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HitomiProtocolDeviceTest {
    @Test
    fun galleryAssignmentParserIsCompatibleWithAndroidRuntime() {
        val gallery = HitomiProtocol.parseGalleryAssignment(
            """
            const galleryinfo = {
              "id": 4042375,
              "type": "artistcg"
            };
            """.trimIndent(),
        )

        assertEquals(4_042_375, gallery.get("id").asInt)
        assertEquals("artistcg", gallery.get("type").asString)
    }
}
