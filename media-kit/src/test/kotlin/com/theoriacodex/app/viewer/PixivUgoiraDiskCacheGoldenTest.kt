package com.theoriacodex.app.viewer

import com.theoriacodex.data.storage.assertStoredFormat
import org.junit.Test

class PixivUgoiraDiskCacheGoldenTest {
    @Test
    fun `the ugoira sidecar older builds wrote decodes the same`() {
        assertStoredFormat(
            checkNotNull(javaClass.getResource("/golden/ugoira-cache/gson-written.json")).readText() to UgoiraCacheIndex(
                version = 1,
                archiveBytes = 64L,
                archiveModifiedMs = 1_700_000_000_000L,
                frames = listOf(UgoiraCacheFrame("000000.jpg", 80), UgoiraCacheFrame("000001 & <b>.jpg", 16)),
            ),
        )
    }
}
