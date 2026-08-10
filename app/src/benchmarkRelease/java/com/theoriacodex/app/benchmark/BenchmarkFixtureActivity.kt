package com.theoriacodex.app.benchmark

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.theoriacodex.app.R
import com.theoriacodex.app.search.SearchResultCard
import com.theoriacodex.app.media.MediaDurationState
import com.theoriacodex.app.media.mediaDurationKeysByPostId
import com.theoriacodex.app.ui.theme.TheoriaNightTheme
import com.theoriacodex.app.viewer.ViewerScreen
import com.theoriacodex.app.viewer.state.ViewerAction
import com.theoriacodex.app.viewer.state.ViewerSessionIdentity
import com.theoriacodex.app.viewer.state.createViewerUiState
import com.theoriacodex.app.viewer.state.reduceViewerState
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey

/** Exists only in benchmarkRelease and never initializes production repositories or providers. */
class BenchmarkFixtureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scenario = intent?.getStringExtra(EXTRA_SCENARIO)
        if (scenario == SCENARIO_SEARCH_DURATION) BenchmarkDurationStartSignal.reset()
        val mediaUri = "android.resource://$packageName/${R.raw.benchmark_loop}".toUri().toString()
        setContent {
            TheoriaNightTheme {
                when (scenario) {
                    SCENARIO_SEARCH -> BenchmarkSearchFixture(
                        mediaUri = mediaUri,
                        durationEnrichmentEnabled = false,
                    )
                    SCENARIO_SEARCH_DURATION -> BenchmarkSearchFixture(
                        mediaUri = mediaUri,
                        durationEnrichmentEnabled = true,
                    )
                    SCENARIO_VIEWER -> BenchmarkViewerFixture(mediaUri)
                    else -> error("Unknown benchmark fixture scenario: $scenario")
                }
            }
        }
    }

    private companion object {
        const val EXTRA_SCENARIO = "benchmark_scenario"
        const val SCENARIO_SEARCH = "search"
        const val SCENARIO_SEARCH_DURATION = "search_duration"
        const val SCENARIO_VIEWER = "viewer"
    }
}

@Composable
private fun BenchmarkSearchFixture(
    mediaUri: String,
    durationEnrichmentEnabled: Boolean,
) {
    val posts = remember(mediaUri, durationEnrichmentEnabled) {
        benchmarkPosts(
            prefix = "benchmark_search",
            count = SEARCH_POST_COUNT,
            mediaUri = mediaUri,
            durationMs = if (durationEnrichmentEnabled) null else BENCHMARK_MEDIA_DURATION_MS,
        )
    }
    val scope = rememberCoroutineScope()
    val resources = LocalContext.current.resources
    val workload = remember(mediaUri, durationEnrichmentEnabled) {
        if (durationEnrichmentEnabled) {
            BenchmarkDurationWorkload(
                scope = scope,
                resources = resources,
                initialPosts = posts,
            )
        } else {
            null
        }
    }
    if (workload != null) {
        DisposableEffect(workload) {
            onDispose(workload::close)
        }
        LaunchedEffect(workload) {
            BenchmarkDurationStartSignal.generation.collect { generation ->
                if (generation > 0L) workload.start()
            }
        }
    }
    val durationStates = workload?.states?.collectAsState()?.value.orEmpty()
    val durationKeysByPostId = remember(posts) { mediaDurationKeysByPostId(posts) }
    val resolvedCount = posts.count { post ->
        post.durationMs != null ||
            durationKeysByPostId[post.id]?.let(durationStates::get) is MediaDurationState.Known
    }
    val statusDescription = when {
        resolvedCount == SEARCH_POST_COUNT -> DURATION_SETTLED_DESCRIPTION
        resolvedCount == 0 -> "Waiting 0/$SEARCH_POST_COUNT"
        else -> "Resolving $resolvedCount/$SEARCH_POST_COUNT"
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (durationEnrichmentEnabled) {
                    Modifier
                        .semantics {
                            testTagsAsResourceId = true
                            contentDescription = statusDescription
                        }
                        .testTag(DURATION_STATUS_TAG)
                } else {
                    Modifier
                },
            ),
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxSize()
                .semantics { testTagsAsResourceId = true }
                .testTag(SEARCH_GRID_TAG),
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(posts, key = { post -> post.id.sourcePostId }) { post ->
                SearchResultCard(
                    post = post,
                    pixivUgoiraClient = null,
                    acquiredDurationMs = durationKeysByPostId[post.id]
                        ?.let(durationStates::get)
                        ?.let { state -> (state as? MediaDurationState.Known)?.durationMs },
                    playbackDiagnosticsEnabled = true,
                    onClick = {},
                )
            }
        }
    }
}

@Composable
private fun BenchmarkViewerFixture(mediaUri: String) {
    val posts = remember(mediaUri) {
        benchmarkPosts(
            prefix = "benchmark_viewer",
            count = VIEWER_POST_COUNT,
            mediaUri = mediaUri,
            durationMs = BENCHMARK_MEDIA_DURATION_MS,
        )
    }
    var viewerState by remember(posts) {
        mutableStateOf(
            createViewerUiState(
                session = ViewerSessionIdentity("benchmark-viewer-session"),
                posts = posts,
            ),
        )
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .semantics { testTagsAsResourceId = true },
    ) {
        ViewerScreen(
            uiState = viewerState,
            creatorBrowsingSources = emptySet(),
            onAction = { action: ViewerAction ->
                viewerState = reduceViewerState(viewerState, action).state
            },
            onOpenInBrowser = {},
            onRemoveIncludeTerm = { _, _ -> },
            onRemoveExcludeTerm = { _, _ -> },
            onGoToSearch = {},
            playbackDiagnosticsEnabled = true,
        )
    }
}

private fun benchmarkPosts(
    prefix: String,
    count: Int,
    mediaUri: String,
    durationMs: Long?,
): List<Post> {
    return List(count) { index ->
        val media = ImageRef(
            url = mediaUri,
            localPath = null,
            mime = "video/mp4",
            isAnimated = true,
        )
        Post(
            id = PostId(SourceKey.RULE34VIDEO, "${prefix}_$index"),
            preview = ImageRef(url = null, localPath = null, mime = "image/jpeg"),
            full = media,
            media = listOf(media),
            pageUrl = null,
            width = 640,
            height = 360,
            canonicalTags = listOf("benchmark"),
            rawTags = listOf("benchmark"),
            authorName = "Offline fixture",
            createdAtEpochMs = index.toLong(),
            title = "Benchmark video ${index + 1}",
            durationMs = durationMs,
            mediaCount = 1,
        )
    }
}

private const val SEARCH_GRID_TAG = "benchmark_search_grid"
internal const val SEARCH_POST_COUNT = 24
private const val VIEWER_POST_COUNT = 6
private const val BENCHMARK_MEDIA_DURATION_MS = 2_000L
