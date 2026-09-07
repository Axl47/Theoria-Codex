package com.theoriacodex.app.benchmark

import android.content.Context
import android.os.Trace
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import com.theoriacodex.app.R
import com.theoriacodex.app.appshell.AppShellViewModel
import com.theoriacodex.app.appshell.ViewerSessionRetentionViewModel
import com.theoriacodex.app.codex.profileScopedCodexId
import com.theoriacodex.app.di.TheoriaAppContainer
import com.theoriacodex.app.fixtures.JourneyAppContainer
import com.theoriacodex.app.fixtures.JourneyNoNetworkHttpClient
import com.theoriacodex.app.media.MediaDurationState
import com.theoriacodex.app.media.mediaDurationKey
import com.theoriacodex.app.search.LAST_ACTIVE_QUERY_KEY
import com.theoriacodex.app.search.state.modeKey
import com.theoriacodex.app.ui.TheoriaAppContent
import com.theoriacodex.app.viewer.BinaryResponse
import com.theoriacodex.app.viewer.PixivUgoiraClient
import com.theoriacodex.app.viewer.TextResponse
import com.theoriacodex.app.viewer.videoPlaybackInfrastructure
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.PIXIV_UGOIRA_MIME
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.credentials.PixivAuthTokens
import com.theoriacodex.sources.http.SourceByteRange
import com.theoriacodex.sources.http.SourceByteResponse
import com.theoriacodex.sources.http.SourceHttpClient
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** A full production navigation graph. Only provider responses, media bytes and storage are fixture-owned. */
internal class BenchmarkAppJourney private constructor(
    private val fixture: JourneyAppContainer,
    val container: TheoriaAppContainer,
    val posts: List<Post>,
    val transport: BenchmarkByteTransport,
    private val storage: File,
) : AutoCloseable {
    val sessionId = storage.name

    override fun close() {
        fixture.close()
        // Each activity owns a fresh directory inside the isolated benchmark application sandbox.
        storage.deleteRecursively()
    }

    companion object {
        suspend fun create(context: Context, scope: CoroutineScope): BenchmarkAppJourney = withContext(Dispatchers.IO) {
            val storage = File(context.cacheDir, "benchmark-journey-${System.nanoTime()}").apply { mkdirs() }
            val mediaUri = "android.resource://${context.packageName}/${R.raw.benchmark_motion}"
            val transport = BenchmarkByteTransport(mediaUri, context.resources.openRawResource(R.raw.benchmark_motion).use { it.readBytes() })
            val posts = journeyPosts(context)
            val fixture = JourneyAppContainer(context, storage, mapOf(SourceKey.PIXIV to posts), scope,
                pageSize = 12, httpClient = transport)
            fixture.awaitReady()
            fixture.sources.accounts.savePixivTokens(PixivAuthTokens("offline-fixture", "offline-fixture", Long.MAX_VALUE))
            val ugoira = PixivUgoiraClient(
                fixture.sources.accounts, JourneyNoNetworkHttpClient, archiveDirectory = storage.resolve("ugoira"),
                metadataFetcher = { _, _ -> TextResponse(200,
                    """{"ugoira_metadata":{"zip_urls":{"medium":"https://fixture.invalid/ugoira.zip"},"frames":[{"file":"0.png","delay":120},{"file":"1.png","delay":120}]}}""") },
                zipDownloader = { _, _, destination ->
                    context.resources.openRawResource(R.raw.benchmark_ugoira).use { input ->
                        destination.outputStream().use(input::copyTo)
                    }
                    BinaryResponse(200)
                },
            )
            val container = object : TheoriaAppContainer by fixture {
                override val sources = fixture.sources.copy(pixivUgoiraClient = ugoira)
            }
            val settings = fixture.settings.observeSettings().first()
            val profile = settings.recommendationProfiles.first { it.profileId == settings.activeProfileId }
            fixture.workflows.likesCodexSync.toggle(profile, posts.first(), listOf("benchmark"))
            val codexId = profileScopedCodexId(profile.profileId, "benchmark")
            fixture.content.ensureCodex(codexId, BENCHMARK_COLLECTION_NAME)
            fixture.content.addItems(codexId, posts)
            posts.asReversed().forEach { post ->
                fixture.data.recentsRepository.recordWatchedPost(post, ViewerStreamSource.SEARCH, "benchmark")
            }
            val query = Query(QueryMode.Source(SourceKey.PIXIV), emptyList<String>(), emptyList(), SortMode.NEWEST, null, null)
            fixture.data.queryRepository.upsertAppliedQuery(modeKey(query.mode), query)
            fixture.data.queryRepository.upsertAppliedQuery(LAST_ACTIVE_QUERY_KEY, query)
            BenchmarkAppJourney(fixture, container, posts, transport, storage)
        }
    }
}

@Composable
internal fun BenchmarkAppJourneyContent(
    journey: BenchmarkAppJourney,
    context: Context,
    viewer: ViewerSessionRetentionViewModel,
    shell: AppShellViewModel,
    durationEnabled: Boolean,
) {
    val states by journey.container.features.mediaDurationCoordinator.states.collectAsState()
    val keys = remember(journey) { journey.posts.map(::mediaDurationKey) }
    val settled = keys.count { key -> states[key].let { it is MediaDurationState.Known || it is MediaDurationState.Unsupported } }
    var requested by remember(journey) { mutableStateOf(false) }
    LaunchedEffect(journey, durationEnabled) {
        if (durationEnabled) {
            BenchmarkDurationStartSignal.generation.collect { generation ->
                if (generation > 0 && !requested) {
                    requested = true
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Trace.beginAsyncSection(TRACE_JOURNEY_DURATION_BATCH, 1)
                    journey.container.data.settingsRepository.setResolveUnknownAnimatedDurations(true)
                }
            }
        }
    }
    LaunchedEffect(settled, requested) {
        if (requested && settled == keys.size && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Trace.endAsyncSection(TRACE_JOURNEY_DURATION_BATCH, 1)
        }
    }
    val pool = remember(context) { context.videoPlaybackInfrastructure().feedPreviewPlayerPool }
    var activePlayers by remember { mutableStateOf(0) }
    LaunchedEffect(pool) {
        while (true) {
            activePlayers = pool.activePlayerCount()
            delay(200)
        }
    }
    val status = "Journey v2 id=${journey.sessionId} settled=$settled/${keys.size} ranges=${journey.transport.requests.get()} " +
        "bytes=${journey.transport.bytes.get()} cancelled=${journey.transport.cancelled.get()} active=$activePlayers"
    Box(Modifier.fillMaxSize().semantics {
        testTagsAsResourceId = true
        contentDescription = status
    }.testTag(BENCHMARK_JOURNEY_STATUS)) {
        TheoriaAppContent(journey.container, viewer, shell)
    }
}

/** Serves the real bundled MP4 through the production bounded range parser, with deterministic latency. */
internal class BenchmarkByteTransport(
    private val mediaUri: String,
    private val media: ByteArray,
) : SourceHttpClient by JourneyNoNetworkHttpClient {
    val requests = AtomicInteger()
    val cancelled = AtomicInteger()
    val bytes = AtomicLong()

    override suspend fun getBytes(
        url: String,
        query: Map<String, String>,
        headers: Map<String, String>,
        range: SourceByteRange?,
        maxBodyBytes: Int,
    ): SourceByteResponse {
        require(url == mediaUri) { "Only the bundled duration fixture can be requested" }
        requireNotNull(range)
        require(maxBodyBytes <= 256 * 1024)
        requests.incrementAndGet()
        Trace.beginSection("TheoriaFixtureByteRange")
        Trace.endSection()
        var completed = false
        try {
            delay(250)
            val start = range.startInclusive.toInt()
            val end = minOf(range.endInclusive + 1, media.size.toLong()).toInt()
            require(start in 0 until end && end - start <= maxBodyBytes)
            val body = media.copyOfRange(start, end)
            val responseBytes = bytes.addAndGet(body.size.toLong())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Trace.setCounter("TheoriaFixtureResponseBytes", responseBytes)
            }
            completed = true
            return SourceByteResponse(206, body, mapOf("Content-Range" to listOf("bytes $start-${end - 1}/${media.size}")))
        } finally {
            if (!completed) cancelled.incrementAndGet()
        }
    }
}

private fun journeyPosts(context: Context): List<Post> = List(24) { index ->
    val kind = listOf("video", "gif", "webp", "ugoira")[index % 4]
    val resource = when (kind) {
        "video" -> R.raw.benchmark_motion
        "gif" -> R.raw.benchmark_two_frame_gif
        "webp" -> R.raw.benchmark_two_frame_webp
        else -> R.raw.benchmark_ugoira
    }
    val mime = when (kind) {
        "video" -> "video/mp4"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        else -> PIXIV_UGOIRA_MIME
    }
    val media = ImageRef("android.resource://${context.packageName}/$resource", null, mime, isAnimated = true)
    Post(
        id = PostId(SourceKey.PIXIV, "benchmark_${kind}_$index"),
        preview = if (kind == "video" || kind == "ugoira") ImageRef(null, null, "image/jpeg") else media,
        full = media, media = listOf(media), pageUrl = null, width = 640, height = 360,
        canonicalTags = listOf("benchmark"), rawTags = listOf("benchmark"), authorName = "Benchmark creator",
        createdAtEpochMs = index.toLong(), title = "Benchmark $kind ${index + 1}", mediaCount = 1,
        creatorProfile = CreatorProfile(SourceKey.PIXIV, "Benchmark creator", profileId = "benchmark-creator", uploadsQuery = "benchmark"),
    )
}

internal const val BENCHMARK_JOURNEY_STATUS = "benchmark_journey_status"
internal const val BENCHMARK_COLLECTION_NAME = "Benchmark collection"
internal const val TRACE_JOURNEY_DURATION_BATCH = "TheoriaJourneyDurationBatch"
