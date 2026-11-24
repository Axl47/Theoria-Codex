package com.theoriacodex.app.journeys

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.theoriacodex.app.codex.profileScopedCodexId
import com.theoriacodex.app.recommend.FOR_YOU_CHIPS_TAG
import com.theoriacodex.app.recommend.FOR_YOU_CRUMBS_TAG
import com.theoriacodex.app.recommend.FOR_YOU_CRUMB_TAG_PREFIX
import com.theoriacodex.app.recommend.FOR_YOU_FORK_TAG_PREFIX
import com.theoriacodex.app.recommend.FOR_YOU_GRID_TAG
import com.theoriacodex.app.recommend.FOR_YOU_ORIGIN_TAG
import com.theoriacodex.app.recommend.FOR_YOU_SAVE_SEARCH_TAG
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

/**
 * The For You deck with real owners: chips, forks and their crumbs, Save search, the grid, what a like brings, and
 * drags. Each journey saves one screenshot of its key moment ([saveScreenshot]).
 */
class ForYouDeckJourneyTest : AppJourneyFixture() {
    /** A second creator draws every third post, and one early post is animated. */
    override fun customizePost(index: Int, post: Post): Post {
        val source = post.id.source
        val drawn = if (index % 3 != 1) post else post.copy(
            authorName = "Second artist",
            creatorProfile = CreatorProfile(source, "Second artist", "second-artist", uploadsQuery = "second-artist"),
        )
        if (index != ANIMATED_INDEX) return drawn
        // Beside the fixture's image, in the folder the journey deletes when it ends.
        val animation = solidAnimation(File(checkNotNull(post.preview.localPath)).resolveSibling("pulse-${source.id}.gif"))
        return drawn.copy(preview = animation, full = animation)
    }

    @Test
    fun aChipNarrowsTheDeckToItsInterestAndMixWidensIt() {
        openDeck()
        val chips = compose.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag(FOR_YOU_CHIPS_TAG))).fetchSemanticsNodes()
        val interest = chips.map { it.text() }.last { it != "Mix" }
        activate(compose.onNode(chip(interest)))
        waitFor("deck origin is $interest") { origin() == interest }
        repeat(3) {
            decide("Skip")
            assertEquals(interest, origin())
        }
        saveScreenshot("deck-chip-narrowed")

        activate(compose.onNode(chip("Mix")))
        waitFor("Mix chip is selected") { compose.onNode(chip("Mix")).fetchSemanticsNode().config[SemanticsProperties.Selected] }
        val widened = (0 until 6).any { (origin() != interest).also { other -> if (!other) decide("Skip") } }
        assertTrue("The Mix dealt only $interest after widening", widened)
    }

    @Test
    fun aTagForkShowsCrumbsAndBackReturnsToTheSameMixCard() {
        openDeck()
        decide("Skip")
        val card = currentCard()
        val tag = forkByTag()
        assertEquals("Mix", compose.onNodeWithTag("${FOR_YOU_CRUMB_TAG_PREFIX}0").fetchSemanticsNode().text())
        assertEquals(tag, compose.onNodeWithTag("${FOR_YOU_CRUMB_TAG_PREFIX}1").fetchSemanticsNode().text())
        assertTrue(compose.onAllNodesWithTag(FOR_YOU_CHIPS_TAG).fetchSemanticsNodes().isEmpty())
        saveScreenshot("deck-tag-trail")

        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
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
        val brought = (0 until 6).any { (origin() == "Like one you liked").also { found -> if (!found) decide("Skip") } }
        assertTrue("No card like the liked one in the next six", brought)
        saveScreenshot("deck-like-one-you-liked")
        assertEquals(1, io { container.data.likesRepository.observeLikedPostIds("profile-main").first() }.size)
    }

    @Test
    fun aSaveBringsACardLikeItThatSaysItWasASave() {
        io { container.content.ensureCodex(profileScopedCodexId("profile-main", "journey-saves"), "Journey saves") }
        openDeck()
        decide { it.startsWith("Save to ") }
        val brought = (0 until 6).any { (origin() == "Like one you saved").also { found -> if (!found) decide("Skip") } }
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
        val scrolled = topGridCard()
        assertNotEquals(first, scrolled)

        // Forks are offered on deck cards: open the card's creator from the deck, then come back to the grid.
        activate(compose.onNodeWithTag(FOR_YOU_VIEW_TOGGLE_TAG))
        waitFor("deck shows a card") { currentCardOrNull() != null }
        waitFor("creator fork is offered") { compose.onAllNodesWithTag("${FOR_YOU_FORK_TAG_PREFIX}creator").fetchSemanticsNodes().isNotEmpty() }
        activate(compose.onNodeWithTag("${FOR_YOU_FORK_TAG_PREFIX}creator"))
        waitFor("creator crumb 2 is displayed") { compose.onAllNodesWithTag("${FOR_YOU_CRUMB_TAG_PREFIX}2").fetchSemanticsNodes().isNotEmpty() }
        activate(compose.onNodeWithTag(FOR_YOU_VIEW_TOGGLE_TAG))
        waitFor("grid view is displayed") { compose.onNodeWithTag(FOR_YOU_GRID_TAG).isDisplayed() }

        // The crumb leaves the creator's trail as Back does.
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
    }

    @Test
    fun aCardDragNeverChangesTab() {
        openDeck()
        val deck = compose.onNodeWithTag("triage-deck")
        val first = currentCard()
        // A drag short of a swipe: the card springs back, and no tab moved under it.
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

    /** Decides the card through the action the deck gives TalkBack, as a swipe would, and waits for the next card. */
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

    /** Opens the first tag fork the current card offers and returns its label. */
    private fun forkByTag(): String {
        val fork = SemanticsMatcher("a tag fork") { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("${FOR_YOU_FORK_TAG_PREFIX}tag:") == true
        }
        waitFor("tag fork is offered") { compose.onAllNodes(fork).fetchSemanticsNodes().isNotEmpty() }
        val label = compose.onAllNodes(fork).onFirst().fetchSemanticsNode().text()
        activate(compose.onAllNodes(fork).onFirst())
        waitFor("crumb 1 is displayed after forking") { compose.onAllNodesWithTag("${FOR_YOU_CRUMB_TAG_PREFIX}1").fetchSemanticsNodes().isNotEmpty() }
        return label
    }

    private fun chip(label: String) = hasClickAction() and hasAnyAncestor(hasTestTag(FOR_YOU_CHIPS_TAG)) and
        SemanticsMatcher("reads $label") { it.text() == label }

    /** What the card says about why it is here; null while no card shows. */
    private fun origin(): String? = compose.onAllNodesWithTag(FOR_YOU_ORIGIN_TAG).fetchSemanticsNodes().singleOrNull()?.text()

    private fun currentCard(): String = checkNotNull(currentCardOrNull()) { "The deck shows no card" }

    /** The test tag of the deck's card; at rest the deck composes only that one. */
    private fun currentCardOrNull(): String? {
        val card = SemanticsMatcher("a deck card") { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("search_card_") == true
        }
        return compose.onAllNodes(card and hasAnyAncestor(hasTestTag("triage-deck"))).fetchSemanticsNodes()
            .singleOrNull()?.config?.get(SemanticsProperties.TestTag)
    }

    /** The test tag of the highest card showing in the grid. */
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

    /** A two-frame GIF on disk, each frame one colour, standing in for animated provider media. */
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

/** A 32 px square GIF that alternates cyan and magenta every half second, forever. */
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
    // A global table of two colours.
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
        // Codes of three bits with a clear code before every two pixels, so the code width never grows.
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
