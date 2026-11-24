package com.theoriacodex.app.search

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TagSuggestionSeedDataTest {
    @Test
    fun `the bundled seed asset loads its Pixiv suggestions`() {
        val seeds = loadSeedTagSuggestions(ApplicationProvider.getApplicationContext<Context>())

        assertEquals("1boy", seeds.getValue(SourceKey.PIXIV).first().text)
    }

    @Test
    fun `a malformed seed entry skips only itself`() {
        val seeds = parseSeedTagSuggestions(
            """{"sources":{"PIXIV":[{"text":"before"},{"text":["not","text"]},{"text":"after","count":3}]}}""",
        )

        assertEquals(listOf("before", "after"), seeds.getValue(SourceKey.PIXIV).map { it.text })
    }
}
