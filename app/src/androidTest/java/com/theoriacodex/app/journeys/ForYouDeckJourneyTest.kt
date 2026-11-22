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
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.theoriacodex.app.recommend.FOR_YOU_CHIPS_TAG
import com.theoriacodex.app.recommend.FOR_YOU_CRUMBS_TAG
import com.theoriacodex.app.recommend.FOR_YOU_CRUMB_TAG_PREFIX
import com.theoriacodex.app.recommend.FOR_YOU_FORK_TAG_PREFIX
import com.theoriacodex.app.recommend.FOR_YOU_GRID_TAG
import com.theoriacodex.app.recommend.FOR_YOU_ORIGIN_TAG
import com.theoriacodex.app.recommend.FOR_YOU_SAVE_SEARCH_TAG
import com.theoriacodex.app.recommend.FOR_YOU_UNDO_TAG
import com.theoriacodex.app.recommend.FOR_YOU_VIEW_TOGGLE_TAG
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
        compose.waitUntil(10_000) { origin() == interest }
        repeat(3) {
            decide("Skip")
            assertEquals(interest, origin())
        }
        saveScreenshot("deck-chip-narrowed")

        activate(compose.onNode(chip("Mix")))
        compose.waitUntil(10_000) { compose.onNode(chip("Mix")).fetchSemanticsNode().config[SemanticsProperties.Selected] }
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
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(FOR_YOU_CRUMBS_TAG).fetchSemanticsNodes().isEmpty() }
        assertEquals(card, currentCard())
        compose.onNodeWithTag(FOR_YOU_ORIGIN_TAG).assertIsDisplayed()
    }

    @Test
    fun saveSearchOnATagTrailCreatesAnOrdinarySavedSearch() {
        openDeck()
        val tag = forkByTag()
        activate(compose.onNodeWithTag(FOR_YOU_SAVE_SEARCH_TAG))
        compose.waitUntil(10_000) { savedSearches().isNotEmpty() }

        val saved = savedSearches().single().search
        assertEquals(RecentSearchKind.SOURCE, saved.kind)
        assertTrue(saved.query.mode is QueryMode.Source)
        assertEquals(listOf(tag), saved.query.includeTags.map { it.replace('_', ' ') })
        assertTrue(saved.sourceTags.isEmpty())
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Saved").fetchSemanticsNodes().isNotEmpty() }
        saveScreenshot("deck-trail-saved")
    }

    @Test
    fun theGridShowsTheDeckPostsAndSurvivesARecreatedActivity() {
        openDeck()
        val card = currentCard()
        activate(compose.onNodeWithTag(FOR_YOU_VIEW_TOGGLE_TAG))
        compose.waitUntil(10_000) { compose.onNodeWithTag(FOR_YOU_GRID_TAG).isDisplayed() }
        compose.onNodeWithTag(card).assertIsDisplayed()
        saveScreenshot("deck-as-grid")

        scenario.recreate()
        compose.waitUntil(15_000) { compose.onNodeWithTag(FOR_YOU_GRID_TAG).isDisplayed() }
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
        compose.waitUntil(10_000) { currentCard() != first }
        val second = currentCard()
        deck.performTouchInput { swipeRight() }
        compose.waitUntil(10_000) { currentCard() != second }
        assertNotEquals(first, currentCard())
        compose.onNodeWithTag(FOR_YOU_UNDO_TAG).assertIsDisplayed()
        saveScreenshot("deck-after-swipes")
    }

    private fun openDeck() {
        launch()
        tab("For You")
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(FOR_YOU_ORIGIN_TAG).fetchSemanticsNodes().isNotEmpty() }
    }

    /** Decides the card through the action the deck gives TalkBack, as a swipe would, and waits for the next card. */
    private fun decide(label: String) {
        val offers = SemanticsMatcher("offers $label") { node ->
            node.config.getOrNull(SemanticsActions.CustomActions).orEmpty().any { it.label == label }
        }
        val before = currentCard()
        val action = compose.onNode(offers).fetchSemanticsNode().config[SemanticsActions.CustomActions].first { it.label == label }
        compose.runOnUiThread { action.action() }
        compose.waitUntil(10_000) { currentCardOrNull().let { it != null && it != before } }
    }

    /** Opens the first tag fork the current card offers and returns its label. */
    private fun forkByTag(): String {
        val fork = SemanticsMatcher("a tag fork") { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("${FOR_YOU_FORK_TAG_PREFIX}tag:") == true
        }
        compose.waitUntil(10_000) { compose.onAllNodes(fork).fetchSemanticsNodes().isNotEmpty() }
        val label = compose.onAllNodes(fork).onFirst().fetchSemanticsNode().text()
        activate(compose.onAllNodes(fork).onFirst())
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("${FOR_YOU_CRUMB_TAG_PREFIX}1").fetchSemanticsNodes().isNotEmpty() }
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
