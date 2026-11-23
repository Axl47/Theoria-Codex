package com.theoriacodex.app.ui.routes

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.theoriacodex.app.fixtures.JourneyAppContainer
import com.theoriacodex.app.series.FakeSeriesSource
import com.theoriacodex.app.series.SeriesReader
import com.theoriacodex.app.series.StoredOnly
import com.theoriacodex.app.series.seriesDependencies
import com.theoriacodex.app.viewer.ViewerPlaybackProgress
import com.theoriacodex.app.viewer.ViewerSession
import com.theoriacodex.app.viewer.state.ViewerSessionIdentity
import com.theoriacodex.data.repository.StoredSeries
import com.theoriacodex.data.repository.StoredSeriesEntry
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.MediaStream
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.EntryMedia
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the Viewer's route does with an anime series session: one source of two episodes, each lookup with fresh links. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SeriesViewerWorkflowTest {
    @get:Rule val temporary = TemporaryFolder()

    private val anime = SeriesId(SourceKey.parseOrThrow("custom_aniyomi_7"), "/anime/1")
    private val source = FakeSeriesSource(anime.source) { asked ->
        EntryMedia.Streams(listOf("1080p", "720p").map { label -> MediaStream("https://video.test/$asked/$label.m3u8", label) })
    }
    private val repository = StoredOnly(
        StoredSeries(Series(anime, "Title")),
        (2 downTo 1).mapIndexed { order, number -> StoredSeriesEntry(SeriesEntry("/e$number", "Ep. $number"), order, 0) },
    )

    @Test
    fun `an episode on screen records no page and stays out of History, and its playback goes to its series`() = runTest {
        withWorkflow { graph, workflow, session, episode ->
            workflow.recordVisiblePost(episode, 1, session)
            workflow.recordVisibleMediaProgress(episode, 1, session)
            assertEquals(emptyList<String>(), repository.progress)

            workflow.recordPlaybackProgress(episode, ViewerPlaybackProgress(positionMs = 61_000, durationMs = 1_440_000), session)
            assertEquals(listOf("/e1 61000/1440000"), repository.progress)
            assertTrue(graph.data.recentsRepository.observeWatchedPosts().first().isEmpty())
        }
    }

    @Test
    fun `an episode whose links expired is looked up again for fresh streams under the same labels`() = runTest {
        withWorkflow { _, workflow, session, episode ->
            val recovered = checkNotNull(
                workflow.recoverMedia(episode, episode.media.single(), ViewerStreamSource.SERIES, session.context.queryHash),
            )

            assertEquals(2, source.asked)
            assertEquals(listOf("1080p", "720p"), recovered.media.single().streams.map { it.label })
            assertEquals("https://video.test/2/1080p.m3u8", recovered.media.single().streams.first().url)
        }
    }

    /** Opens episode 1 of [anime] in a series session and looks it up, as the Viewer does when it gets there. */
    private suspend fun TestScope.withWorkflow(block: suspend (JourneyAppContainer, ViewerRouteWorkflow, ViewerSession, Post) -> Unit) {
        val graph = JourneyAppContainer(ApplicationProvider.getApplicationContext(), temporary.newFolder(), emptyMap(), this)
        try {
            graph.awaitReady()
            val reader = SeriesReader(seriesDependencies(temporary.newFolder(), repository, { listOf(source) }))
            val workflow = ViewerRouteWorkflow(graph.data, this, graph.sources, { null }, graph.features.offlineMedia, series = reader)
            val session = checkNotNull(reader.session(anime, "/e1"))
            val identity = ViewerSessionIdentity(session.sessionId, session.context.queryHash, ViewerStreamSource.SERIES.name)
            val episode = checkNotNull(workflow.resolve(identity, session.posts.first().id))
            block(graph, workflow, session, episode)
        } finally {
            graph.shutdown()
        }
    }
}
