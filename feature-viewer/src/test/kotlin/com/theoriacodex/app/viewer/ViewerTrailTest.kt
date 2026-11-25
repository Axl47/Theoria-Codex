package com.theoriacodex.app.viewer

import com.theoriacodex.app.ui.navigation.TrailFork
import com.theoriacodex.app.ui.navigation.TrailRequest
import com.theoriacodex.app.ui.navigation.request
import com.theoriacodex.app.viewer.ViewerOwnerTestData.post
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ViewerTrailTest {
    private val creator = CreatorProfile(SourceKey.PIXIV, "Mika")

    @Test
    fun `a creator's fork names the trail its level was opened as, from any of the creator's posts`() {
        val fork = TrailFork.Creator(creator).request(post("p"), null)

        assertEquals(TrailRequest.Creator(creator, post("q")).trailKey(), fork.trailKey())
        assertNotEquals(TrailRequest.Creator(creator.copy(displayName = "Other"), post("p")).trailKey(), fork.trailKey())
    }

    private fun tag(value: String) = ViewerCrumb(value, "tag:DANBOORU:$value", CrumbTag(value, SourceKey.DANBOORU))

    @Test
    fun `removing a middle step goes back before it and re-runs the later steps in order (ADR 0217)`() {
        val path = listOf(ViewerCrumb("landscape"), tag("night_sky"), tag("aurora"), tag("snow"))

        assertEquals(TrailRemoval(backTo = 0, replay = listOf("aurora", "snow")), path.removal(1))
    }

    @Test
    fun `removing the current step is going back one step`() {
        val path = listOf(ViewerCrumb("landscape"), tag("night_sky"), tag("aurora"))

        assertEquals(TrailRemoval(backTo = 1, replay = emptyList()), path.removal(2))
    }

    @Test
    fun `the re-run ends before a creator or Similar step (ADR 0217)`() {
        val path = listOf(ViewerCrumb("landscape"), tag("night_sky"), tag("aurora"), ViewerCrumb("Mika", "creator:PIXIV:Mika"), tag("snow"))

        assertEquals(TrailRemoval(backTo = 0, replay = listOf("aurora")), path.removal(1))
    }

    @Test
    fun `only tag steps after the first can be removed`() {
        val path = listOf(tag("landscape"), ViewerCrumb("Mika", "creator:PIXIV:Mika"), tag("aurora"))

        assertNull(path.removal(0))
        assertNull(path.removal(1))
    }
}
