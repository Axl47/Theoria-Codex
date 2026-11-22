package com.theoriacodex.app.series

import com.theoriacodex.app.di.SeriesDependencies
import com.theoriacodex.app.viewer.decodeViewerPostId
import com.theoriacodex.app.viewer.encodeViewerPostId
import com.theoriacodex.data.repository.FollowedSeries
import com.theoriacodex.data.repository.SeriesRepository
import com.theoriacodex.data.repository.StoredSeries
import com.theoriacodex.data.repository.StoredSeriesEntry
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.GalleryReadingMode
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.theoriacodex.domain.series.ExtensionSetting
import com.theoriacodex.domain.series.InstalledExtension
import com.theoriacodex.domain.series.SeriesExtensions
import kotlinx.coroutines.flow.MutableStateFlow

class SeriesReaderTest {
    private val source = SourceKey.parseOrThrow("custom_mihon_2499283573021220255")
    private val id = SeriesId(source, "/manga/a1b2:c3?lang=en")

    @Test
    fun chapterAndSeriesAddressesSurviveTheViewersSavedState() {
        val chapter = PostId(source, "/chapter/9f:8e/1?x=a:b")

        assertEquals(chapter, decodeViewerPostId(encodeViewerPostId(chapter)))
        assertEquals(id, seriesIdOf(seriesQueryHash(id)))
        assertNull(seriesIdOf("codex:saved"))
    }

    @Test
    fun aSessionHoldsEveryChapterOldestFirstAndResumesEachWhereItWasLeft() = runTest {
        // The source lists the newest first, so order 0 is chapter 3.
        val entries = listOf(
            StoredSeriesEntry(SeriesEntry("/c3", "Ch. 3"), sourceOrder = 0, firstSeenAtEpochMs = 0),
            StoredSeriesEntry(SeriesEntry("/c2", "Ch. 2"), sourceOrder = 1, firstSeenAtEpochMs = 0, lastPage = 7, pageCount = 18),
            StoredSeriesEntry(SeriesEntry("/c1", "Ch. 1"), sourceOrder = 2, firstSeenAtEpochMs = 0, read = true, lastPage = 20, pageCount = 20),
        )
        val reader = SeriesReader(SeriesDependencies(StoredOnly(StoredSeries(Series(id, "Title")), entries), sources = { emptyList() }, extensions = NoExtensions))

        val session = checkNotNull(reader.session(id, "/c2"))

        assertEquals(listOf("/c1", "/c2", "/c3"), session.posts.map { it.id.sourcePostId })
        assertEquals(1, session.context.startIndex)
        assertEquals(ViewerStreamSource.SERIES, session.context.streamSource)
        assertEquals("Title · Ch. 2", session.posts[1].title)
        assertTrue(session.posts.all { it.galleryLengthUnknown && it.media.isEmpty() })
        // Page 7 is index 6; a finished chapter starts over.
        assertEquals(6, session.initialMediaIndices[PostId(source, "/c2")])
        assertEquals(0, session.initialMediaIndices[PostId(source, "/c1")])
        assertNull(reader.session(id, "/missing"))
    }
}

/** A repository that only answers what is stored; the reader's session needs nothing else. */
private class StoredOnly(private val stored: StoredSeries, private val entries: List<StoredSeriesEntry>) : SeriesRepository {
    override suspend fun series(id: SeriesId) = stored.takeIf { it.series.id == id }
    override suspend fun entries(id: SeriesId) = entries
    override fun observeFollowed(): Flow<List<FollowedSeries>> = emptyFlow()
    override fun observeSeries(id: SeriesId): Flow<StoredSeries?> = emptyFlow()
    override fun observeEntries(id: SeriesId): Flow<List<StoredSeriesEntry>> = emptyFlow()
    override suspend fun store(series: Series, entries: List<SeriesEntry>?) = Unit
    override suspend fun setFollowed(id: SeriesId, followed: Boolean) = Unit
    override suspend fun setReadingMode(id: SeriesId, mode: GalleryReadingMode) = Unit
    override suspend fun recordProgress(id: SeriesId, entryAddress: String, page: Int, pageCount: Int?) = Unit
}

/** The reader never asks about extensions. */
private object NoExtensions : SeriesExtensions {
    override val revision = MutableStateFlow(0)

    override suspend fun installed(): List<InstalledExtension> = emptyList()

    override suspend fun setOn(id: String, on: Boolean) = Unit

    override suspend fun setLanguage(id: String, language: String, on: Boolean) = Unit

    override suspend fun change(id: String, sourceId: String, setting: ExtensionSetting) = Unit
}
