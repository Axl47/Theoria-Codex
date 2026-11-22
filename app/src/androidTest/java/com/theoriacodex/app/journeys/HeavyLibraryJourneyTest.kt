package com.theoriacodex.app.journeys

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import com.theoriacodex.app.codex.profileScopedCodexId
import com.theoriacodex.app.fixtures.HeavyHistory
import com.theoriacodex.app.library.LibrarySection
import com.theoriacodex.app.recommend.FOR_YOU_CHIPS_TAG
import com.theoriacodex.app.recommend.FOR_YOU_END_TAG
import com.theoriacodex.app.recommend.FOR_YOU_UNDO_TAG
import com.theoriacodex.app.search.searchCardTestTag
import com.theoriacodex.app.ui.routes.activeRecommendationProfile
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.PostId
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The app opened on a long history ([HeavyHistory]): many Likes, Codices that share posts with them, and hundreds of
 * watched posts. Covers what only that history decides: which For You lanes lead, how hiding one of their tags takes
 * the lane and its cards until Undo, a Viewer post's Trail chips, which Codex a post is suggested for, and Likes at scale.
 */
class HeavyLibraryJourneyTest : AppJourneyFixture() {
    private lateinit var history: HeavyHistory

    /** The deck cards decided so far, for failure messages. */
    private val dealt = mutableListOf<PostId>()

    override fun fixturePosts(media: ImageRef) = mapOf(HeavyHistory.SOURCE to HeavyHistory(media).also { history = it }.catalog)

    /** Seeds the history through the app's own Likes, Codex and statistics owners, as using the app would. */
    @Before
    fun seedHistory() = io {
        val profile = container.settings.observeSettings().first().activeRecommendationProfile()
        history.likes.forEach { post -> container.workflows.likesCodexSync.toggle(profile, post, post.canonicalTags) }
        history.codices.forEach { (name, posts) ->
            val codex = container.content.ensureCodex(codexId(name), name)
            container.content.addItems(codex.codexId, posts)
        }
        history.watched.forEach { tags -> container.data.statisticsRepository.recordWatchedPost(HeavyHistory.SOURCE, tags.toSet()) }
    }

    @Test
    fun theMixLeadsWithWhatTheProfileSeeksAndAHiddenTagTakesItsLaneUntilUndo() {
        launch()
        tab("For You")
        compose.waitUntil(20_000) { visibleLaneChips().size > 1 }
        // Not blush (on most of what is watched), smile (kept as often as watched) or artist_name (text on the image).
        val lanes = setOf(setOf("Mix"), setOf("frieren", "fern"), setOf("hatsune miku"), setOf("kitagawa marin"))
        assertEquals(lanes.size, laneChipCount())
        assertEquals(lanes, allLaneChips())

        // Hiding takes the lane and its cards, widening to the Mix; Undo brings back both.
        activate(compose.onNode(chip("hatsune miku")))
        compose.waitUntil(10_000) { history.groupOf(currentPost()) == "miku" }
        val hiddenCard = currentPost()
        hideMiku()
        compose.waitUntil(10_000) { visibleLaneChips() == lanes - setOf(setOf("hatsune miku")) }
        assertNotEquals("miku", history.groupOf(currentPost()))
        activate(compose.onNodeWithTag(FOR_YOU_UNDO_TAG))
        compose.waitUntil(10_000) { hiddenTags().isEmpty() }
        compose.waitUntil(15_000) { visibleLaneChips() == lanes }
        compose.waitUntil(10_000) { currentPostOrNull() == hiddenCard }

        // Hidden for good: no card left in the deck carries it, through to the deck's end.
        hideMiku()
        repeat(MAX_DEALS) {
            if (deckEnded()) return
            assertNotEquals("miku", history.groupOf(currentPost()))
            decide("Skip")
        }
        throw AssertionError("The deck did not end after $MAX_DEALS cards: ${dealt.map(history::groupOf)}")
    }

    @Test
    fun aViewerPostsTrailChipsAndSaveSuggestionComeFromTheHistory() {
        val post = history.freshFrieren
        val index = posts.getValue(HeavyHistory.SOURCE).indexOf(post)
        openSearchPost(index, "heavy:sketch", HeavyHistory.SOURCE, listOf(HeavyHistory.FRESH_TAG))
        // The post lists fern first, but frieren is on more kept posts; blush and artist_name never make a chip.
        val expected = listOf("frieren", "fern")
        runCatching { compose.waitUntil(15_000) { trailTags() == expected } }
        assertEquals(expected, trailTags())

        val save = compose.onNodeWithTag("viewer-dock-save")
        compose.waitUntil(10_000) { save.isDisplayed() && save.description() == "Save to ${HeavyHistory.FRIEREN_CODEX}" }
        activate(save)
        compose.waitUntil(10_000) { post.id in codexPosts(HeavyHistory.FRIEREN_CODEX) }
    }

    @Test
    fun likesAtScaleCountOnceBesideTheirCodicesAndUnlikingKeepsTheCodexCopy() {
        launch()
        openSettings()
        // Most Codex posts are also Liked; each saved post counts once.
        compose.waitUntil(10_000) { showsText("${history.watched.size} watched · ${history.savedPosts} saved") }

        back()
        library(LibrarySection.Saved)
        activate(likesShelf(history.likes.size))
        val unliked = history.likes.last()
        assertTrue(unliked.id in codexPosts(HeavyHistory.FRIEREN_CODEX))
        activate(compose.onNodeWithTag(searchCardTestTag(unliked.id)))
        activate(compose.onNode(hasContentDescription("Unlike post") and hasClickAction()))
        compose.waitUntil(10_000) { unliked.id !in likedIds() }
        assertEquals(history.likes.size - 1, likedIds().size)
        assertTrue(unliked.id in codexPosts(HeavyHistory.FRIEREN_CODEX))

        repeat(2) { back() }
        library(LibrarySection.Saved)
        compose.waitUntil(10_000) { likesShelf(history.likes.size - 1).isDisplayed() }
        openSettings()
        // Still saved in its Codex, so the saved count holds.
        compose.waitUntil(10_000) { showsText(" · ${history.savedPosts} saved") }
    }

    private fun back() {
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    /** Hides `hatsune_miku` from the deck's card, which must carry it. */
    private fun hideMiku() {
        perform("Hide hatsune miku")
        compose.waitUntil(10_000) { hiddenTags() == setOf("hatsune_miku") }
    }

    /** The tags of each lane chip on screen, "Mix" included; the paired lane's order varies between Mixes. */
    private fun visibleLaneChips(): Set<Set<String>> =
        compose.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag(FOR_YOU_CHIPS_TAG))).fetchSemanticsNodes()
            .mapTo(mutableSetOf()) { it.text().split(" + ").toSet() }

    /** [visibleLaneChips] across the whole row, scrolling it so chips past the screen's edge count too. */
    private fun allLaneChips(): Set<Set<String>> {
        val row = compose.onNodeWithTag(FOR_YOU_CHIPS_TAG)
        val chips = (0 until laneChipCount()).flatMapTo(mutableSetOf()) { index ->
            row.performScrollToIndex(index)
            visibleLaneChips()
        }
        row.performScrollToIndex(0)
        return chips
    }

    /** How many chips the row holds, "Mix" included: the first index it cannot scroll to. */
    private fun laneChipCount(): Int {
        val row = compose.onNodeWithTag(FOR_YOU_CHIPS_TAG)
        val count = generateSequence(0) { it + 1 }.first { index -> runCatching { row.performScrollToIndex(index) }.isFailure }
        row.performScrollToIndex(0)
        return count
    }

    private fun chip(label: String) = hasClickAction() and hasAnyAncestor(hasTestTag(FOR_YOU_CHIPS_TAG)) and
        SemanticsMatcher("reads $label") { it.text() == label }

    private fun deckEnded() = compose.onAllNodesWithTag(FOR_YOU_END_TAG).fetchSemanticsNodes().isNotEmpty()

    private fun offering(label: String) = SemanticsMatcher("offers $label") { node ->
        node.config.getOrNull(SemanticsActions.CustomActions).orEmpty().any { it.label == label }
    }

    private fun perform(label: String) {
        compose.waitUntil(10_000) { compose.onAllNodes(offering(label)).fetchSemanticsNodes().isNotEmpty() }
        val action = compose.onNode(offering(label)).fetchSemanticsNode().config[SemanticsActions.CustomActions].first { it.label == label }
        compose.runOnUiThread { action.action() }
    }

    /** Decides the deck's card and waits for the next one, or for the deck's end. */
    private fun decide(label: String) {
        val before = currentPost()
        dealt += before
        perform(label)
        compose.waitUntil(10_000) { currentPostOrNull().let { it != null && it != before } || deckEnded() }
    }

    private fun currentPost(): PostId {
        try {
            compose.waitUntil(10_000) { currentPostOrNull() != null }
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError("The deck shows no card (end shown: ${deckEnded()}) after ${dealt.map(history::groupOf)}", timeout)
        }
        return checkNotNull(currentPostOrNull())
    }

    /** The post of the deck's card; at rest the deck composes only that one. */
    private fun currentPostOrNull(): PostId? {
        val card = SemanticsMatcher("a deck card") { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("search_card_") == true
        }
        val tag = compose.onAllNodes(card and hasAnyAncestor(hasTestTag("triage-deck"))).fetchSemanticsNodes()
            .singleOrNull()?.config?.get(SemanticsProperties.TestTag) ?: return null
        return history.catalog.first { searchCardTestTag(it.id) == tag }.id
    }

    /** The Viewer's tag chips, in the order the row shows them. */
    private fun trailTags(): List<String> = compose.onAllNodes(SemanticsMatcher("a tag chip") { node ->
        node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(TRAIL_TAG_PREFIX) == true
    }).fetchSemanticsNodes().map { it.config[SemanticsProperties.TestTag].removePrefix(TRAIL_TAG_PREFIX) }

    /** The Library's Likes shelf heading while it shows [count] Likes. */
    private fun likesShelf(count: Int) = compose.onNode(hasText("Likes") and hasText(count.toString()) and hasClickAction())

    private fun showsText(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun hiddenTags(): Set<String> = io {
        val settings = container.data.settingsRepository.observeSettings().first()
        settings.forYouBlacklistByProfile[settings.activeProfileId].orEmpty().flatMapTo(mutableSetOf()) { it.tags }
    }

    private fun likedIds(): Set<PostId> = io { container.data.likesRepository.observeLikedPostIds("profile-main").first() }

    private fun codexPosts(name: String): List<PostId> = io {
        container.content.observeCodexItems(codexId(name)).first().map { it.postId }
    }

    private fun codexId(name: String) = profileScopedCodexId("profile-main", name.lowercase().replace(' ', '-'))

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.description(): String? =
        fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()

    private fun SemanticsNode.text(): String = config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()

    private companion object {
        const val TRAIL_TAG_PREFIX = "viewer-trail-chip-tag:"
        const val MAX_DEALS = 20
    }
}
