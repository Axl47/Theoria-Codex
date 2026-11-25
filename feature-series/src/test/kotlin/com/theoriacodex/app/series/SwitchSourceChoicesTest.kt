package com.theoriacodex.app.series

import androidx.compose.ui.state.ToggleableState
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.EntryMedia
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntries
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesFilter
import com.theoriacodex.domain.series.SeriesPage
import com.theoriacodex.domain.series.SeriesSource
import com.theoriacodex.domain.series.languageName
import org.junit.Assert.assertEquals
import org.junit.Test

class SwitchSourceChoicesTest {
    private val english = Named("custom_mihon_2", "Paper Lantern", "en")
    private val spanish = Named("custom_mihon_3", "Paper Lantern", "es")
    private val alone = Named("custom_mihon_4", "Tin Harbor", "en")

    @Test
    fun sourcesOfOneNameFoldIntoOneGroupInTheirOwnOrderWithTheirLanguages() {
        val groups = sourceGroups(listOf(english, alone, spanish))

        assertEquals(listOf("Paper Lantern", "Tin Harbor"), groups.map { it.name })
        assertEquals(listOf(languageName("en"), languageName("es")), groups[0].choices.map { it.language })
        assertEquals(listOf(english.key, spanish.key), groups[0].keys)
        assertEquals(listOf(alone.key), groups[1].keys)
    }

    @Test
    fun aGroupIsCheckedUncheckedOrPartlyChecked() {
        val group = sourceGroups(listOf(english, spanish)).single()

        assertEquals(ToggleableState.On, group.state(emptySet()))
        assertEquals(ToggleableState.Indeterminate, group.state(setOf(spanish.key)))
        assertEquals(ToggleableState.Off, group.state(setOf(english.key, spanish.key)))
    }

    @Test
    fun aPartlyOrUncheckedGroupChecksEveryLanguageAndACheckedOneUnchecksThemAll() {
        val keys = listOf(english.key, spanish.key)
        val elsewhere = alone.key

        assertEquals(setOf(elsewhere), setOf(spanish.key, elsewhere).toggled(keys))
        assertEquals(setOf(elsewhere), setOf(english.key, spanish.key, elsewhere).toggled(keys))
        assertEquals(setOf(elsewhere, english.key, spanish.key), setOf(elsewhere).toggled(keys))
    }
}

private class Named(id: String, override val name: String, override val language: String) : SeriesSource {
    override val key = SourceKey.parseOrThrow(id)
    override suspend fun popular(page: Int) = SeriesPage(emptyList(), hasNextPage = false)
    override suspend fun search(text: String, page: Int, filters: List<SeriesFilter>) = SeriesPage(emptyList(), hasNextPage = false)
    override suspend fun details(series: Series) = series
    override suspend fun entries(series: Series, stored: List<SeriesEntry>) = SeriesEntries(emptyList())
    override suspend fun media(entry: SeriesEntry) = EntryMedia.Pages(emptyList())
}
