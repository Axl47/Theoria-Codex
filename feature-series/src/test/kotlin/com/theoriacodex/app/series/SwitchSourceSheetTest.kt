package com.theoriacodex.app.series

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import com.theoriacodex.app.testing.waitUntilSettled
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.EntryMedia
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntries
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesFilter
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesPage
import com.theoriacodex.domain.series.SeriesSource
import com.theoriacodex.domain.series.languageName
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h914dp-xxhdpi")
class SwitchSourceSheetTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun aSourceThatFailsIsWrittenAtTheSizeOfTheSheetsOtherSourceLines() {
        val viewModel = SwitchSourceViewModel(SeriesId(SourceKey.parseOrThrow("custom_mihon_1"), "/mine"), { listOf(Unreachable) }, store = {}, choices = MemorySwitchSourceSkips()) { _, _ -> false }
        compose.setContent { MaterialTheme { SwitchSourceSheet(viewModel, onOpenSeries = {}, onSwitched = {}) } }
        compose.waitUntilSettled { viewModel.state.value.others.isNotEmpty() }
        viewModel.open("Kaiju")
        compose.waitUntilSettled { viewModel.state.value.sheet?.results?.singleOrNull()?.listing is SwitchListing.Failed }
        compose.waitForIdle()

        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Tin Harbor", substring = true).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        assertEquals(Typography().bodyMedium.fontSize, layouts.single().layoutInput.style.fontSize)
    }

    @Test
    fun aLanguageGroupsBoxUnchecksEveryLanguageAndTheCountFollows() {
        val english = Lantern("custom_mihon_2", "en")
        val spanish = Lantern("custom_mihon_3", "es")
        val skips = MemorySwitchSourceSkips(setOf(spanish.key))
        val viewModel = SwitchSourceViewModel(SeriesId(SourceKey.parseOrThrow("custom_mihon_1"), "/mine"), { listOf(english, spanish) }, store = {}, choices = skips) { _, _ -> false }
        compose.setContent { MaterialTheme { SwitchSourceSheet(viewModel, onOpenSeries = {}, onSwitched = {}) } }
        compose.waitUntilSettled { viewModel.state.value.others.isNotEmpty() }
        viewModel.open("Kaiju")
        compose.onNodeWithText("1 of 2").assertExists()

        compose.onNodeWithText("Sources").performClick()
        compose.onNodeWithContentDescription("Show languages").performClick()
        compose.onNodeWithText(languageName("es")).assertIsOff()
        compose.onNodeWithText("Paper Lantern").assertIsToggleableIndeterminate().performClick()
        compose.onNodeWithText("Paper Lantern").assertIsOn()
        compose.onNodeWithText("Paper Lantern").performClick()

        compose.onNodeWithText("0 of 2").assertExists()
        compose.onNodeWithText("No sources").assertExists()
        compose.waitUntilSettled { skips.skipped == setOf(english.key, spanish.key) }
    }
}

private fun SemanticsNodeInteraction.assertIsToggleableIndeterminate(): SemanticsNodeInteraction =
    assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Indeterminate))

private class Lantern(id: String, override val language: String) : SeriesSource {
    override val key: SourceKey = SourceKey.parseOrThrow(id)
    override val name = "Paper Lantern"
    override suspend fun popular(page: Int) = SeriesPage(emptyList(), hasNextPage = false)
    override suspend fun search(text: String, page: Int, filters: List<SeriesFilter>) = SeriesPage(emptyList(), hasNextPage = false)
    override suspend fun details(series: Series) = series
    override suspend fun entries(series: Series, stored: List<SeriesEntry>) = SeriesEntries(emptyList())
    override suspend fun media(entry: SeriesEntry) = EntryMedia.Pages(emptyList())
}

private object Unreachable : SeriesSource {
    override val key: SourceKey = SourceKey.parseOrThrow("custom_mihon_2")
    override val name = "Tin Harbor"
    override val language = "en"
    override suspend fun popular(page: Int) = SeriesPage(emptyList(), hasNextPage = false)
    override suspend fun search(text: String, page: Int, filters: List<SeriesFilter>): SeriesPage = throw IOException("unreachable")
    override suspend fun details(series: Series) = series
    override suspend fun entries(series: Series, stored: List<SeriesEntry>) = SeriesEntries(emptyList())
    override suspend fun media(entry: SeriesEntry) = EntryMedia.Pages(emptyList())
}
