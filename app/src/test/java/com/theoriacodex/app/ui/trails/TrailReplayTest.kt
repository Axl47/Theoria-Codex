package com.theoriacodex.app.ui.trails

import androidx.lifecycle.SavedStateHandle
import com.theoriacodex.app.ui.navigation.TrailRequest
import com.theoriacodex.app.ui.trails.TrailTestData.post
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Test

class TrailReplayTest {
    private fun tagRequest(tag: String) = TrailRequest.Tag(tag, SourceKey.DANBOORU, null, post("p", SourceKey.DANBOORU))

    @Test
    fun `opening the next replayed step hands the rest of the path to the new level (ADR 0217)`() {
        val level = SavedStateHandle().apply { planTrailReplay(listOf("aurora", "snow")) }

        assertEquals(listOf("snow"), level.takeTrailReplay(tagRequest("aurora")))
        assertEquals(emptyList<String>(), level.takeTrailReplay(tagRequest("aurora")))
    }

    @Test
    fun `opening anything else from the level drops the planned replay (ADR 0217)`() {
        val level = SavedStateHandle().apply { planTrailReplay(listOf("aurora", "snow")) }

        assertEquals(emptyList<String>(), level.takeTrailReplay(tagRequest("lake")))
        assertEquals(emptyList<String>(), level.takeTrailReplay(tagRequest("aurora")))
    }
}
