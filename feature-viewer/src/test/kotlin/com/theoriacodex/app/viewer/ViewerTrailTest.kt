package com.theoriacodex.app.viewer

import com.theoriacodex.app.ui.navigation.TrailFork
import com.theoriacodex.app.ui.navigation.TrailRequest
import com.theoriacodex.app.ui.navigation.request
import com.theoriacodex.app.viewer.ViewerOwnerTestData.post
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ViewerTrailTest {
    private val creator = CreatorProfile(SourceKey.PIXIV, "Mika")

    @Test
    fun `a creator's fork names the trail its level was opened as, from any of the creator's posts`() {
        val fork = TrailFork.Creator(creator).request(post("p"), null)

        assertEquals(TrailRequest.Creator(creator, post("q")).trailKey(), fork.trailKey())
        assertNotEquals(TrailRequest.Creator(creator.copy(displayName = "Other"), post("p")).trailKey(), fork.trailKey())
    }
}
