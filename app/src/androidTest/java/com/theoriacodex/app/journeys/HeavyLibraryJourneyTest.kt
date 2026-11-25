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
import com.theoriacodex.app.codex.profileScopedCodexId
import com.theoriacodex.app.fixtures.HeavyHistory
import com.theoriacodex.app.library.LibrarySection
import com.theoriacodex.app.recommend.FOR_YOU_END_TAG
import com.theoriacodex.app.recommend.FOR_YOU_FILTERS_TAG
import com.theoriacodex.app.recommend.FOR_YOU_MIX_LANE_TAG_PREFIX
import com.theoriacodex.app.recommend.FOR_YOU_ORIGIN_TAG
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

class HeavyLibraryJourneyTest : AppJourneyFixture() {
    private lateinit var history: HeavyHistory

    private val dealt = mutableListOf<PostId>()

    override fun fixturePosts(media: ImageRef) = mapOf(HeavyHistory.SOURCE to HeavyHistory(media).also { history = it }.catalog)

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
        compose.waitUntil(20_000) { origin() != null }
        val lanes = setOf(setOf("frieren", "fern"), setOf("hatsune miku"), setOf("kitagawa marin"))
        assertMixLanes(lanes)

        repeat(MAX_DEALS) { if (origin() != "hatsune miku") decide("Skip") }
        assertEquals("miku", history.groupOf(currentPost()))
        val hiddenCard = currentPost()
        hideMiku()
        compose.waitUntil(10_000) { history.groupOf(currentPost()) != "miku" }
        assertMixLanes(lanes - setOf(setOf("hatsune miku")))
        activate(compose.onNodeWithTag(FOR_YOU_UNDO_TAG))
        compose.waitUntil(10_000) { hiddenTags().isEmpty() }
        compose.waitUntil(10_000) { currentPostOrNull() == hiddenCard }
        assertMixLanes(lanes)

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
        compose.waitUntil(10_000) { showsText("${history.watched.size} watched · ${history.savedPosts} saved") }

        back("Settings") { libraryTabShows() }
        library(LibrarySection.Saved)
        activate(likesShelf(history.likes.size))
        val unliked = history.likes.last()
        assertTrue(unliked.id in codexPosts(HeavyHistory.FRIEREN_CODEX))
        activate(compose.onNodeWithTag(searchCardTestTag(unliked.id)))
        activate(compose.onNode(hasContentDescription("Unlike post") and hasClickAction()))
        compose.waitUntil(10_000) { unliked.id !in likedIds() }
        assertEquals(history.likes.size - 1, likedIds().size)
        assertTrue(unliked.id in codexPosts(HeavyHistory.FRIEREN_CODEX))

        back("the Viewer") { !viewerShows() && showsText("Likes") && !libraryTabShows() }
        back("Likes") { libraryTabShows() }
        library(LibrarySection.Saved)
        compose.waitUntil(10_000) { likesShelf(history.likes.size - 1).isDisplayed() }
        openSettings()
        compose.waitUntil(10_000) { showsText(" · ${history.savedPosts} saved") }
    }

    @Test
    fun twoQuickBacksFromTheViewerLandTwoScreensBack() {
        launch()
        library(LibrarySection.Saved)
        activate(likesShelf(history.likes.size))
        val unliked = history.likes.last()
        activate(compose.onNodeWithTag(searchCardTestTag(unliked.id)))
        compose.waitUntil(10_000) { viewerShows() }
        activate(compose.onNode(hasContentDescription("Unlike post") and hasClickAction()))
        compose.waitUntil(10_000) { unliked.id !in likedIds() }

        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.mainClock.advanceTimeByFrame()
        back("Likes, during the Viewer's return") { !viewerShows() && libraryTabShows() }
    }

    private fun back(from: String, arrived: () -> Boolean) {
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        try {
            compose.waitUntil(10_000, arrived)
        } catch (timeout: ComposeTimeoutException) {
            val shows = listOfNotNull("Viewer".takeIf { viewerShows() }, "Library tab".takeIf { libraryTabShows() })
            throw AssertionError("Back from $from did not arrive; showing: ${shows.ifEmpty { listOf("neither Viewer nor Library tab") }}", timeout)
        }
    }

    private fun viewerShows() = compose.onAllNodesWithTag("viewer-dock-save", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun libraryTabShows() = compose.onAllNodes(hasContentDescription("Library")).fetchSemanticsNodes().isNotEmpty()

    private fun hideMiku() {
        perform("Hide hatsune miku")
        compose.waitUntil(10_000) { hiddenTags() == setOf("hatsune_miku") }
    }

    private fun assertMixLanes(expected: Set<Set<String>>) {
        activate(compose.onNodeWithTag(FOR_YOU_FILTERS_TAG))
        runCatching { compose.waitUntil(15_000) { mixLanes() == expected } }
        assertEquals(expected, mixLanes())
        pressSystemBack()
        compose.waitUntil(10_000) { mixLanes().isEmpty() }
    }

    private fun mixLanes(): Set<Set<String>> = compose.onAllNodes(SemanticsMatcher("a Mix lane") { node ->
        node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(FOR_YOU_MIX_LANE_TAG_PREFIX) == true
    }).fetchSemanticsNodes().mapTo(mutableSetOf()) { it.text().split(" + ").toSet() }

    private fun origin(): String? = compose.onAllNodesWithTag(FOR_YOU_ORIGIN_TAG).fetchSemanticsNodes().singleOrNull()?.text()

    private fun deckEnded() = compose.onAllNodesWithTag(FOR_YOU_END_TAG).fetchSemanticsNodes().isNotEmpty()

    private fun offering(label: String) = SemanticsMatcher("offers $label") { node ->
        node.config.getOrNull(SemanticsActions.CustomActions).orEmpty().any { it.label == label }
    }

    private fun perform(label: String) {
        compose.waitUntil(10_000) { compose.onAllNodes(offering(label)).fetchSemanticsNodes().isNotEmpty() }
        val action = compose.onNode(offering(label)).fetchSemanticsNode().config[SemanticsActions.CustomActions].first { it.label == label }
        compose.runOnUiThread { action.action() }
    }

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

    private fun currentPostOrNull(): PostId? {
        val card = SemanticsMatcher("a deck card") { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("search_card_") == true
        }
        val tag = compose.onAllNodes(card and hasAnyAncestor(hasTestTag("triage-deck"))).fetchSemanticsNodes()
            .singleOrNull()?.config?.get(SemanticsProperties.TestTag) ?: return null
        return history.catalog.first { searchCardTestTag(it.id) == tag }.id
    }

    private fun trailTags(): List<String> = compose.onAllNodes(SemanticsMatcher("a tag chip") { node ->
        node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(TRAIL_TAG_PREFIX) == true
    }).fetchSemanticsNodes().map { it.config[SemanticsProperties.TestTag].removePrefix(TRAIL_TAG_PREFIX) }

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
