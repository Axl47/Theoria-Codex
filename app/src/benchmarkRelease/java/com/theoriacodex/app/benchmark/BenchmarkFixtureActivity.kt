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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.theoriacodex.app.R
import com.theoriacodex.app.search.SearchResultCard
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
        val mediaUri = "android.resource://$packageName/${R.raw.benchmark_loop}".toUri().toString()
        setContent {
            TheoriaNightTheme {
                when (scenario) {
                    SCENARIO_SEARCH -> BenchmarkSearchFixture(mediaUri)
                    SCENARIO_VIEWER -> BenchmarkViewerFixture(mediaUri)
                    else -> error("Unknown benchmark fixture scenario: $scenario")
                }
            }
        }
    }

    private companion object {
        const val EXTRA_SCENARIO = "benchmark_scenario"
        const val SCENARIO_SEARCH = "search"
        const val SCENARIO_VIEWER = "viewer"
    }
}

@Composable
private fun BenchmarkSearchFixture(mediaUri: String) {
    val posts = remember(mediaUri) {
        benchmarkPosts(prefix = "benchmark_search", count = SEARCH_POST_COUNT, mediaUri = mediaUri)
    }
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
                playbackDiagnosticsEnabled = true,
                onClick = {},
            )
        }
    }
}

@Composable
private fun BenchmarkViewerFixture(mediaUri: String) {
    val posts = remember(mediaUri) {
        benchmarkPosts(prefix = "benchmark_viewer", count = VIEWER_POST_COUNT, mediaUri = mediaUri)
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

private fun benchmarkPosts(prefix: String, count: Int, mediaUri: String): List<Post> {
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
            durationMs = BENCHMARK_MEDIA_DURATION_MS,
            mediaCount = 1,
        )
    }
}

private const val SEARCH_GRID_TAG = "benchmark_search_grid"
private const val SEARCH_POST_COUNT = 24
private const val VIEWER_POST_COUNT = 6
private const val BENCHMARK_MEDIA_DURATION_MS = 2_000L
