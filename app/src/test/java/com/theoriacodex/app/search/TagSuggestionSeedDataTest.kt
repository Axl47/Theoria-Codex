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
    // The bundled seed asset is the only input; an empty result would silently remove every starter suggestion.
    @Test
    fun `the bundled seed asset loads its Pixiv suggestions`() {
        val seeds = loadSeedTagSuggestions(ApplicationProvider.getApplicationContext<Context>())

        assertEquals("1boy", seeds.getValue(SourceKey.PIXIV).first().text)
    }
}
