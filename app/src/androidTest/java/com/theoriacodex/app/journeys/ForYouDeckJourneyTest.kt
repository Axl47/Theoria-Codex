package com.theoriacodex.app.journeys

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import com.theoriacodex.app.codex.profileScopedCodexId
import com.theoriacodex.app.recommend.FOR_YOU_CRUMBS_TAG
import com.theoriacodex.app.recommend.FOR_YOU_CRUMB_TAG_PREFIX
import com.theoriacodex.app.recommend.FOR_YOU_FORK_ROW_TAG
import com.theoriacodex.app.recommend.FOR_YOU_FORK_TAG_PREFIX
import com.theoriacodex.app.recommend.FOR_YOU_GRID_TAG
import com.theoriacodex.app.recommend.FOR_YOU_FILTERS_TAG
import com.theoriacodex.app.recommend.FOR_YOU_MIX_LANE_TAG_PREFIX
import com.theoriacodex.app.recommend.FOR_YOU_ORIGIN_TAG
import com.theoriacodex.app.recommend.FOR_YOU_SAVE_SEARCH_TAG
import com.theoriacodex.app.recommend.FOR_YOU_REFRESH_TAG
import com.theoriacodex.app.recommend.FOR_YOU_UNDO_TAG
import com.theoriacodex.app.recommend.FOR_YOU_VIEW_TOGGLE_TAG
import com.theoriacodex.app.search.searchCardTestTag
import com.theoriacodex.data.repository.ForYouVerdictKind

import com.theoriacodex.data.repository.RecentSearchKind
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.QueryMode
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForYouDeckJourneyTest : AppJourneyFixture() {
    override fun customizePost(index: Int, post: Post): Post {
        val source = post.id.source
        val drawn = if (index % 3 != 1) post else post.copy(
            authorName = "Second artist",
            creatorProfile = CreatorProfile(source, "Second artist", "second-artist", uploadsQuery = "second-artist"),
        )
        if (index != ANIMATED_INDEX) return drawn
        val animation = solidAnimation(File(checkNotNull(post.preview.localPath)).resolveSibling("pulse-${source.id}.gif"))
        return drawn.copy(preview = animation, full = animation)
    }

    @Test
    fun theOriginPillFocusesInPlaceAndMixResumesPastWhatWasDecidedThere() {
        openDeck()
        decide("Skip")
        val card = currentCard()
        val interest = checkNotNull(origin())
        activate(compose.onNodeWithTag(FOR_YOU_ORIGIN_TAG))
        waitFor("crumb 1 reads $interest") { compose.onAllNodesWithTag("${FOR_YOU_CRUMB_TAG_PREFIX}1").fetchSemanticsNodes().singleOrNull()?.text() == interest }
        assertEquals(card, currentCard())
        saveScreenshot("deck-focused-in-place")

        decide("Skip")
        activate(compose.onNodeWithTag("${FOR_YOU_CRUMB_TAG_PREFIX}0"))
        waitFor("crumbs are gone after Mix") { compose.onAllNodesWithTag(FOR_YOU_CRUMBS_TAG).fetchSemanticsNodes().isEmpty() }
        assertNotEquals(card, currentCard())
        compose.onNodeWithTag(FOR_YOU_ORIGIN_TAG).assertIsDisplayed()
        compose.onNodeWithTag(FOR_YOU_UNDO_TAG).assertIsNotEnabled()
    }

    @Test
    fun aMixLaneInFiltersFocusesTheDeckOnIt() {
        openDeck()
        val card = currentCard()
        val label = checkNotNull(origin())
        activate(compose.onNodeWithTag(FOR_YOU_FILTERS_TAG))
        val lane = SemanticsMatcher("the card's Mix lane") { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(FOR_YOU_MIX_LANE_TAG_PREFIX) == true && node.text() == label
        }
        waitFor("the card's lane is listed") { compose.onAllNodes(lane).fetchSemanticsNodes().isNotEmpty() }
        activate(compose.onNode(lane))
        waitFor("crumb 1 reads $label") { compose.onAllNodesWithTag("${FOR_YOU_CRUMB_TAG_PREFIX}1").fetchSemanticsNodes().singleOrNull()?.text() == label }
        assertEquals(card, currentCard())
        saveScreenshot("deck-lane-focused")
    }

    @Test
    fun aTagForkShowsCrumbsAndBackReturnsToTheSameMixCard() {
        openDeck()
        decide("Skip")
        val card = currentCard()
        val tag = forkByTag()
        assertEquals("Mix", compose.onNodeWithTag("${FOR_YOU_CRUMB_TAG_PREFIX}0").fetchSemanticsNode().text())
        assertEquals(tag, compose.onNodeWithTag("${FOR_YOU_CRUMB_TAG_PREFIX}1").fetchSemanticsNode().text())
        assertEquals(card, currentCard())
        saveScreenshot("deck-tag-trail")

        pressSystemBack()
        waitFor("trail crumbs are gone after Back") { compose.onAllNodesWithTag(FOR_YOU_CRUMBS_TAG).fetchSemanticsNodes().isEmpty() }
        assertEquals(card, currentCard())
        compose.onNodeWithTag(FOR_YOU_ORIGIN_TAG).assertIsDisplayed()
    }

    @Test
    fun saveSearchOnATagTrailCreatesAnOrdinarySavedSearch() {
        openDeck()
        val tag = forkByTag()
        activate(compose.onNodeWithTag(FOR_YOU_SAVE_SEARCH_TAG))
        waitFor("a saved search exists") { savedSearches().isNotEmpty() }

        val saved = savedSearches().single().search
        assertEquals(RecentSearchKind.SOURCE, saved.kind)
        assertTrue(saved.query.mode is QueryMode.Source)
        assertEquals(listOf(tag), saved.query.includeTags.map { it.replace('_', ' ') })
        assertTrue(saved.sourceTags.isEmpty())
        waitFor("Saved indicator is displayed") { compose.onAllNodesWithContentDescription("Saved").fetchSemanticsNodes().isNotEmpty() }
        saveScreenshot("deck-trail-saved")
    }

    @Test
    fun theGridShowsTheDeckPostsAndSurvivesARecreatedActivity() {
        openDeck()
        val card = currentCard()
        activate(compose.onNodeWithTag(FOR_YOU_VIEW_TOGGLE_TAG))
        waitFor("grid view is displayed") { compose.onNodeWithTag(FOR_YOU_GRID_TAG).isDisplayed() }
        compose.onNodeWithTag(card).assertIsDisplayed()
        saveScreenshot("deck-as-grid")

        scenario.recreate()
        waitFor("grid view is displayed after recreating the activity", timeoutMillis = 15_000) { compose.onNodeWithTag(FOR_YOU_GRID_TAG).isDisplayed() }
        compose.onNodeWithTag(card).assertIsDisplayed()
    }

    @Test
    fun aLikeBringsACardLikeItWithinTheNextSixCards() {
        openDeck()
        decide("Like")
        val brought = (0 until 6).any { (origin() == "Similar").also { found -> if (!found) decide("Skip") } }
        assertTrue("No card like the liked one in the next six", brought)
        saveScreenshot("deck-similar-origin")
        assertEquals(1, io { container.data.likesRepository.observeLikedPostIds("profile-main").first() }.size)
    }

    @Test
    fun aSaveBringsACardLikeIt() {
        io { container.content.ensureCodex(profileScopedCodexId("profile-main", "journey-saves"), "Journey saves") }
        openDeck()
        decide { it.startsWith("Save to ") }
        val brought = (0 until 6).any { (origin() == "Similar").also { found -> if (!found) decide("Skip") } }
        assertTrue("No card like the saved one in the next six", brought)
    }

    @Test
    fun aTrailKeepsItsGridPlaceUnderADeeperTrail() {
        openDeck()
        forkByTag()
        activate(compose.onNodeWithTag(FOR_YOU_VIEW_TOGGLE_TAG))
        waitFor("grid view is displayed") { compose.onNodeWithTag(FOR_YOU_GRID_TAG).isDisplayed() }
        val first = topGridCard()
        repeat(3) { compose.onNodeWithTag(FOR_YOU_GRID_TAG).performTouchInput { swipeUp() }; compose.waitForIdle() }
        compose.onNodeWithTag(FOR_YOU_GRID_TAG).performTouchInput { swipeDown(startY = centerY, endY = centerY + 80f, durationMillis = 600) }
        compose.waitForIdle()
        val scrolled = topGridCard()
        assertNotEquals(first, scrolled)

        activate(compose.onNodeWithTag(FOR_YOU_VIEW_TOGGLE_TAG))
        waitFor("deck shows a card") { currentCardOrNull() != null }
        waitFor("creator fork is offered") { compose.onAllNodesWithTag("${FOR_YOU_FORK_TAG_PREFIX}creator").fetchSemanticsNodes().isNotEmpty() }
        activate(compose.onNodeWithTag("${FOR_YOU_FORK_TAG_PREFIX}creator"))
        waitFor("creator crumb 2 is displayed") { compose.onAllNodesWithTag("${FOR_YOU_CRUMB_TAG_PREFIX}2").fetchSemanticsNodes().isNotEmpty() }
        activate(compose.onNodeWithTag(FOR_YOU_VIEW_TOGGLE_TAG))
        waitFor("grid view is displayed") { compose.onNodeWithTag(FOR_YOU_GRID_TAG).isDisplayed() }

        activate(compose.onNodeWithTag("${FOR_YOU_CRUMB_TAG_PREFIX}1"))
        waitFor("creator crumb 2 is gone") { compose.onAllNodesWithTag("${FOR_YOU_CRUMB_TAG_PREFIX}2").fetchSemanticsNodes().isEmpty() }
        waitFor("grid view is back") { compose.onAllNodesWithTag(FOR_YOU_GRID_TAG).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(scrolled, topGridCard())
    }

    @Test
    fun noSourcesEnabledOffersManageSources() {
        io { container.data.settingsRepository.setEnabledSources(emptySet()) }
        launch()
        tab("For You")
        waitFor("No sources enabled message is displayed", timeoutMillis = 15_000) { compose.onAllNodesWithText("No sources enabled").fetchSemanticsNodes().isNotEmpty() }
        activate(compose.onNodeWithText("Manage sources"))
        waitFor("Built-in sources list is displayed") { compose.onAllNodesWithText("Built-in").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun everySwipeIsKeptAcrossARestartAndUndoForgetsIt() {
        openDeck()
        val liked = currentCard()
        decide("Like")
        val skipped = currentCard()
        decide("Skip")
        val undone = currentCard()
        decide("Skip")
        activate(compose.onNodeWithTag(FOR_YOU_UNDO_TAG))
        waitFor("deck shows the undone card") { currentCardOrNull() == undone }
        waitFor("2 verdicts are recorded") { verdicts().size == 2 }

        coldGraphRelaunch()
        assertEquals(
            listOf(skipped to ForYouVerdictKind.SKIPPED, liked to ForYouVerdictKind.LIKED),
            verdicts().map { searchCardTestTag(it.postId) to it.kind },
        )

        tab("For You")
        compose.waitUntil(15_000) { currentCardOrNull() != null }
        assertNotEquals(skipped, currentCard())
        activate(compose.onNodeWithTag(FOR_YOU_REFRESH_TAG))
        compose.waitUntil(15_000) { currentCardOrNull() != null }
        assertNotEquals(skipped, currentCard())
    }

    @Test
    fun aCardDragNeverChangesTab() {
        openDeck()
        val deck = compose.onNodeWithTag("triage-deck")
        val first = currentCard()
        deck.performTouchInput {
            down(center)
            moveBy(Offset(-width * 0.15f, 0f))
            advanceEventTime(300)
            moveBy(Offset.Zero)
            advanceEventTime(300)
            up()
        }
        compose.waitForIdle()
        assertEquals(first, currentCard())
        compose.onNodeWithTag(FOR_YOU_UNDO_TAG).assertIsDisplayed()

        deck.performTouchInput { swipeLeft() }
        waitFor("deck advanced after swiping left") { currentCard() != first }
        val second = currentCard()
        deck.performTouchInput { swipeRight() }
        waitFor("deck advanced after swiping right") { currentCard() != second }
        assertNotEquals(first, currentCard())
        compose.onNodeWithTag(FOR_YOU_UNDO_TAG).assertIsDisplayed()
        saveScreenshot("deck-after-swipes")
    }

    private fun openDeck() {
        launch()
        tab("For You")
        waitFor("deck origin is displayed", timeoutMillis = 15_000) { compose.onAllNodesWithTag(FOR_YOU_ORIGIN_TAG).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun decide(label: String) = decide { it == label }

    private fun decide(label: (String) -> Boolean) {
        val offers = SemanticsMatcher("offers the action") { node ->
            node.config.getOrNull(SemanticsActions.CustomActions).orEmpty().any { label(it.label) }
        }
        val before = currentCard()
        val action = compose.onNode(offers).fetchSemanticsNode().config[SemanticsActions.CustomActions].first { label(it.label) }
        compose.runOnUiThread { action.action() }
        waitFor("deck advanced to a new card after the decision") { currentCardOrNull().let { it != null && it != before } }
    }

    private fun forkByTag(): String {
        val fork = SemanticsMatcher("a tag fork") { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("${FOR_YOU_FORK_TAG_PREFIX}tag:") == true
        }
        waitFor("the pill row is shown") { compose.onAllNodesWithTag(FOR_YOU_FORK_ROW_TAG).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(FOR_YOU_FORK_ROW_TAG).performScrollToNode(fork)
        val label = compose.onAllNodes(fork).onFirst().fetchSemanticsNode().text()
        activate(compose.onAllNodes(fork).onFirst())
        waitFor("crumb 1 is displayed after forking") { compose.onAllNodesWithTag("${FOR_YOU_CRUMB_TAG_PREFIX}1").fetchSemanticsNodes().isNotEmpty() }
        return label
    }

    private fun origin(): String? = compose.onAllNodesWithTag(FOR_YOU_ORIGIN_TAG).fetchSemanticsNodes().singleOrNull()?.text()

    private fun currentCard(): String = checkNotNull(currentCardOrNull()) { "The deck shows no card" }

    private fun currentCardOrNull(): String? {
        val card = SemanticsMatcher("a deck card") { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("search_card_") == true
        }
        return compose.onAllNodes(card and hasAnyAncestor(hasTestTag("triage-deck"))).fetchSemanticsNodes()
            .singleOrNull()?.config?.get(SemanticsProperties.TestTag)
    }

    private fun topGridCard(): String {
        val card = SemanticsMatcher("a grid card") { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("search_card_") == true
        }
        val viewport = compose.onNodeWithTag(FOR_YOU_GRID_TAG).fetchSemanticsNode().boundsInRoot
        return compose.onAllNodes(card and hasAnyAncestor(hasTestTag(FOR_YOU_GRID_TAG))).fetchSemanticsNodes()
            .filter { it.boundsInRoot.height > 0f && it.boundsInRoot.bottom > viewport.top && it.boundsInRoot.top < viewport.bottom }
            .minWith(compareBy({ it.boundsInRoot.top }, { it.boundsInRoot.left }))
            .config[SemanticsProperties.TestTag]
    }

    private fun verdicts() = io { container.data.forYouVerdicts.verdicts("profile-main") }

    private fun savedSearches() = io { container.data.savedSearches.observeSavedSearches().first() }

    private fun solidAnimation(file: File): ImageRef {
        file.writeBytes(twoFrameGif())
        return ImageRef(null, file.absolutePath, "image/gif", isAnimated = true)
    }

    private companion object {
        const val ANIMATED_INDEX = 2
    }
}

private fun androidx.compose.ui.semantics.SemanticsNode.text(): String =
    config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
        ?: error("Node $id has no text")

private fun twoFrameGif(): ByteArray {
    val size = 32
    val out = ByteArrayOutputStream()
    fun short(value: Int) {
        out.write(value and 0xFF)
        out.write(value shr 8 and 0xFF)
    }
    out.write("GIF89a".toByteArray())
    short(size)
    short(size)
    out.write(byteArrayOf(0x80.toByte(), 0, 0, 0, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0, 0xFF.toByte()))
    out.write(byteArrayOf(0x21, 0xFF.toByte(), 11) + "NETSCAPE2.0".toByteArray() + byteArrayOf(3, 1, 0, 0, 0))
    repeat(2) { color ->
        out.write(byteArrayOf(0x21, 0xF9.toByte(), 4, 0, 50, 0, 0, 0))
        out.write(0x2C)
        short(0)
        short(0)
        short(size)
        short(size)
        out.write(0)
        out.write(2)
        val bits = buildList {
            repeat(size * size / 2) { addAll(listOf(4, color, color)) }
            add(5)
        }
        var buffer = 0
        var filled = 0
        val data = ByteArrayOutputStream()
        bits.forEach { code ->
            buffer = buffer or (code shl filled)
            filled += 3
            while (filled >= 8) {
                data.write(buffer and 0xFF)
                buffer = buffer shr 8
                filled -= 8
            }
        }
        if (filled > 0) data.write(buffer and 0xFF)
        data.toByteArray().toList().chunked(255).forEach { block ->
            out.write(block.size)
            out.write(block.toByteArray())
        }
        out.write(0)
    }
    out.write(0x3B)
    return out.toByteArray()
}
