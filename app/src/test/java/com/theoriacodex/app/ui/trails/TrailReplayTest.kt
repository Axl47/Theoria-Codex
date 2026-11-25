package com.theoriacodex.app.ui.trails

import androidx.lifecycle.SavedStateHandle
import com.theoriacodex.app.ui.navigation.TrailRequest
import com.theoriacodex.app.ui.trails.TrailTestData.post
import com.theoriacodex.app.viewer.CrumbTag
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Test

class TrailReplayTest {
    private fun tagRequest(tag: String, source: SourceKey = SourceKey.DANBOORU) = TrailRequest.Tag(tag, source, null, post("p", source))

    private fun tags(vararg values: String) = values.map { CrumbTag(it, SourceKey.DANBOORU) }

    @Test
    fun `the replayed step's level gets the rest of the path, and the plan holds until the open ends (ADR 0217)`() {
        val level = SavedStateHandle().apply { planTrailReplay(tags("aurora", "snow")) }

        assertEquals(tags("snow"), level.trailReplayAfter(tagRequest("aurora")))
        assertEquals(tags("snow"), level.trailReplayAfter(tagRequest("aurora")))
        level.clearTrailReplay()
        assertEquals(emptyList<CrumbTag>(), level.trailReplayAfter(tagRequest("aurora")))
    }

    @Test
    fun `opening anything else from the level drops the planned replay (ADR 0217)`() {
        val level = SavedStateHandle().apply { planTrailReplay(tags("aurora", "snow")) }

        assertEquals(emptyList<CrumbTag>(), level.trailReplayAfter(tagRequest("aurora", SourceKey.GELBOORU)))
        assertEquals(emptyList<CrumbTag>(), level.trailReplayAfter(tagRequest("aurora")))
    }
}
