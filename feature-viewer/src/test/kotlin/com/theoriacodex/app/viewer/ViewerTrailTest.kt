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

    private fun crumbTag(value: String) = CrumbTag(value, SourceKey.DANBOORU)

    private fun tag(value: String) = ViewerCrumb(value, "tag:DANBOORU:$value", crumbTag(value))

    @Test
    fun `a tag's trail moves are Start without a trail, then Add or Remove with Start a new trail (ADR 0217)`() {
        val forked = mutableListOf<String>()
        val removed = mutableListOf<Int>()
        val restarted = mutableListOf<CrumbTag>()
        fun trail(vararg crumbs: ViewerCrumb) = ViewerTrail(crumbs.toList(), null, {}, { removed += it }, onRestart = { restarted += it })
        fun ViewerTrail.moves(value: String) = tagActions(crumbTag(value)) { forked += value }
        fun List<com.theoriacodex.app.tags.TagChipAction>.run(label: String) = first { it.label == label }.run()

        val root = trail(ViewerCrumb("landscape"))
        assertEquals(listOf("Start a trail"), root.moves("snow").map { it.label })
        root.moves("snow").run("Start a trail")

        val path = trail(ViewerCrumb("landscape"), tag("night_sky"), tag("aurora"))
        assertEquals(listOf("Add to trail", "Start a new trail"), path.moves("snow").map { it.label })
        assertEquals(listOf("Remove from trail", "Start a new trail"), path.moves("night_sky").map { it.label })
        path.moves("snow").run("Add to trail")
        path.moves("night_sky").run("Remove from trail")
        path.moves("snow").run("Start a new trail")

        assertEquals(listOf("snow", "snow"), forked)
        assertEquals(listOf(1), removed)
        assertEquals(listOf(crumbTag("snow")), restarted)
    }

    @Test
    fun `removing a middle step goes back before it and re-runs the later steps in order (ADR 0217)`() {
        val path = listOf(ViewerCrumb("landscape"), tag("night_sky"), tag("aurora"), tag("snow"))

        assertEquals(TrailRemoval(backTo = 0, replay = listOf(crumbTag("aurora"), crumbTag("snow"))), path.removal(1))
    }

    @Test
    fun `removing the current step is going back one step`() {
        val path = listOf(ViewerCrumb("landscape"), tag("night_sky"), tag("aurora"))

        assertEquals(TrailRemoval(backTo = 1, replay = emptyList()), path.removal(2))
    }

    @Test
    fun `the re-run ends before a creator or Similar step (ADR 0217)`() {
        val path = listOf(ViewerCrumb("landscape"), tag("night_sky"), tag("aurora"), ViewerCrumb("Mika", "creator:PIXIV:Mika"), tag("snow"))

        assertEquals(TrailRemoval(backTo = 0, replay = listOf(crumbTag("aurora"))), path.removal(1))
    }

    @Test
    fun `only tag steps after the first can be removed`() {
        val path = listOf(tag("landscape"), ViewerCrumb("Mika", "creator:PIXIV:Mika"), tag("aurora"))

        assertNull(path.removal(0))
        assertNull(path.removal(1))
    }
}
