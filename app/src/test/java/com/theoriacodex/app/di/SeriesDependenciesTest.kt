package com.theoriacodex.app.di

import com.theoriacodex.data.repository.FollowedSeries
import com.theoriacodex.data.repository.SeriesRepository
import com.theoriacodex.data.repository.StoredSeries
import com.theoriacodex.data.repository.StoredSeriesEntry
import com.theoriacodex.domain.model.GalleryReadingMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.ExtensionSetting
import com.theoriacodex.domain.series.ExtensionSourceSettings
import com.theoriacodex.domain.series.InstalledExtension
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesExtensions
import com.theoriacodex.domain.series.SeriesId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesDependenciesTest {
    private val followed = MutableStateFlow(emptyList<FollowedSeries>())
    private val aSeries = FollowedSeries(Series(SeriesId(SourceKey.parseOrThrow("custom_mihon_7"), "/title/1"), "Title"), unreadCount = 0)

    @Test fun `the Series tab shows with an extension on the device or a followed series`() = runTest {
        var onDevice = false
        val series = SeriesDependencies(Followed(followed), { emptyList() }, NoExtensions, { onDevice })
        assertFalse(series.tab.first())

        // A restore onto a phone without the extension, or the last extension uninstalled: the shelf stays reachable.
        followed.value = listOf(aSeries)
        assertTrue(series.tab.first())
        followed.value = emptyList()
        assertFalse(series.tab.first())

        onDevice = true
        series.findExtensions()
        assertTrue(series.tab.first())
    }

    @Test fun `startup uses what the last look found and a look is remembered only when it changes`() = runTest {
        var looks = 0
        val remembered = mutableListOf<Boolean>()
        val series = SeriesDependencies(Followed(followed), { emptyList() }, NoExtensions, { looks += 1; false }, extensionSeen = true, rememberExtensionSeen = { remembered += it })
        // Before anything looked at the device, the tab is as it was when the app last ran.
        assertTrue(series.tab.first())
        assertEquals(0, looks)

        series.findExtensions()
        series.findExtensions()
        assertFalse(series.tab.first())
        assertEquals(2 to listOf(false), looks to remembered)
    }

    @Test fun `looks run one at a time so the latest answer stays, and a failure is reported and not thrown`() = runTest {
        val early = CompletableDeferred<Boolean>()
        val late = CompletableDeferred<Boolean>()
        val answers = java.util.concurrent.ConcurrentLinkedQueue(listOf(early, late))
        val failures = mutableListOf<Throwable>()
        val series = SeriesDependencies(
            Followed(followed), { emptyList() }, NoExtensions, { runBlocking { answers.remove().await() } },
            rememberExtensionSeen = { error("disk full") }, reportFailure = { failures += it },
        )
        assertFalse(series.looked.value)
        val first = launch(Dispatchers.Default) { series.findExtensions() }
        val second = launch(Dispatchers.Default) { series.findExtensions() }
        // The later look answers first; it still publishes last.
        late.complete(false)
        early.complete(true)
        joinAll(first, second)

        assertFalse(series.hasExtensions.value)
        assertTrue(series.looked.value)
        // Both answers changed the remembered one, and storing it failed both times without reaching the caller.
        assertEquals(listOf("disk full", "disk full"), failures.map { it.message })
    }
}

private class Followed(private val followed: Flow<List<FollowedSeries>>) : SeriesRepository {
    override fun observeFollowed() = followed
    override fun observeSeries(id: SeriesId): Flow<StoredSeries?> = emptyFlow()
    override fun observeEntries(id: SeriesId): Flow<List<StoredSeriesEntry>> = emptyFlow()
    override suspend fun series(id: SeriesId): StoredSeries? = null
    override suspend fun entries(id: SeriesId) = emptyList<StoredSeriesEntry>()
    override suspend fun store(series: Series, entries: List<SeriesEntry>?) = emptyList<SeriesEntry>()
    override suspend fun storeEntries(id: SeriesId, entries: List<SeriesEntry>) = emptyList<SeriesEntry>()
    override suspend fun setFollowed(id: SeriesId, followed: Boolean) = Unit
    override suspend fun setReadingMode(id: SeriesId, mode: GalleryReadingMode) = Unit
    override suspend fun recordProgress(id: SeriesId, entryAddress: String, page: Int, pageCount: Int?) = Unit
}

private object NoExtensions : SeriesExtensions {
    override val revision = MutableStateFlow(0)
    override suspend fun installed() = emptyList<InstalledExtension>()
    override suspend fun setOn(id: String, signer: String, on: Boolean) = installed()
    override suspend fun setLanguage(id: String, language: String, on: Boolean) = installed()
    override suspend fun settings(id: String) = emptyList<ExtensionSourceSettings>()
    override suspend fun change(id: String, sourceId: String, setting: ExtensionSetting) = settings(id)
}
