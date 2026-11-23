@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.theoriacodex.app.viewer

import android.net.Uri
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VideoFrameSamplingTest {
    @Test
    fun `sample points spread from 5 to 95 percent, or the first frame when the length is unknown`() {
        assertEquals(listOf(500_000L, 3_500_000L, 6_500_000L, 9_500_000L), samplePointsUs(durationMs = 10_000L, count = 4))
        assertEquals(listOf(0L), samplePointsUs(durationMs = null, count = 6))
    }

    @Test
    fun `the network budget stops reads once spent`() {
        val budgeted = BudgetedDataSource(ByteArrayDataSource(ByteArray(100)), maxBytes = 60)
        budgeted.open(DataSpec(Uri.parse("https://cdn.test/video.mp4")))
        val buffer = ByteArray(100)

        assertEquals(60, budgeted.read(buffer, 0, 100))
        assertThrows(NetworkBudgetSpent::class.java) { budgeted.read(buffer, 0, 100) }
    }
}
