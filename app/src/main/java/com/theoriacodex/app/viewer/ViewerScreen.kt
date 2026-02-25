package com.theoriacodex.app.viewer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.domain.model.Post
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ViewerScreen(
    posts: List<Post>,
    launchContext: ViewerLaunchContext,
    onDismiss: () -> Unit,
    onSave: (Post) -> Unit,
    onOpenInBrowser: (Post) -> Unit,
    onAddIncludeTag: (String) -> Unit,
    onAddExcludeTag: (String) -> Unit,
    onGoToSearch: () -> Unit,
) {
    if (posts.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {
            Text("No posts to view", color = MaterialTheme.colorScheme.onBackground)
        }
        return
    }

    val initialIndex = launchContext.startIndex.coerceIn(0, posts.lastIndex)
    val pagerState = rememberPagerState(
        initialPage = initialIndex,
        pageCount = { posts.size },
    )
    var viewerState by remember(posts, initialIndex) {
        mutableStateOf(ViewerState(streamSize = posts.size, currentIndex = initialIndex))
    }
    var showInfoSheet by remember { mutableStateOf(false) }
    var interactionSerial by remember { mutableIntStateOf(0) }
    var dismissDrag by remember { mutableStateOf(0f) }

    fun markInteraction() {
        interactionSerial += 1
        if (!viewerState.chromeVisible) {
            viewerState = viewerState.copy(chromeVisible = true)
        }
    }

    LaunchedEffect(pagerState.currentPage) {
        viewerState = viewerState.withIndex(pagerState.currentPage)
        markInteraction()
    }

    LaunchedEffect(viewerState.chromeVisible, interactionSerial) {
        if (viewerState.chromeVisible) {
            val serial = interactionSerial
            delay(1500)
            if (serial == interactionSerial) {
                viewerState = viewerState.hideChrome()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(viewerState.zoom) {
                if (viewerState.zoom > ViewerState.FIT_SCALE + 0.01f) {
                    return@pointerInput
                }
                detectVerticalDragGestures(
                    onVerticalDrag = { _, dragAmount ->
                        dismissDrag += dragAmount
                        if (dismissDrag > 180f) {
                            onDismiss()
                        }
                    },
                    onDragEnd = { dismissDrag = 0f },
                    onDragCancel = { dismissDrag = 0f },
                )
            }
    ) {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = viewerState.zoom <= ViewerState.FIT_SCALE + 0.01f,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val post = posts[page]
            val transformState = rememberTransformableState { zoomChange, panChange, _ ->
                viewerState = viewerState.transform(
                    zoomChange = zoomChange,
                    panChangeX = panChange.x,
                    panChangeY = panChange.y,
                )
                markInteraction()
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = viewerState.zoom
                        scaleY = viewerState.zoom
                        translationX = viewerState.panX
                        translationY = viewerState.panY
                    }
                    .transformable(
                        state = transformState,
                        canPan = { viewerState.zoom > ViewerState.FIT_SCALE + 0.01f },
                    )
                    .pointerInput(page) {
                        detectTapGestures(
                            onDoubleTap = {
                                viewerState = viewerState.doubleTap()
                                markInteraction()
                            },
                            onTap = {
                                viewerState = viewerState.toggleChrome()
                                interactionSerial += 1
                            },
                        )
                    }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = post.id.source.name,
                            color = MaterialTheme.colorScheme.onBackground,
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = post.id.sourcePostId,
                            color = MaterialTheme.colorScheme.onBackground,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        post.canonicalTags.firstOrNull()?.let { tag ->
                            AssistChip(onClick = {}, label = { Text("#$tag") })
                        }
                    }
                }
            }
        }

        if (viewerState.chromeVisible) {
            ViewerChrome(
                modifier = Modifier.align(Alignment.TopCenter),
                source = posts[viewerState.currentIndex].id.source.name,
                indexLabel = "${viewerState.currentIndex + 1} / ${posts.size}",
                onBack = onDismiss,
            )
            ViewerActionsBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                onSave = {
                    onSave(posts[viewerState.currentIndex])
                    markInteraction()
                },
                onInfo = {
                    showInfoSheet = true
                    markInteraction()
                },
            )
        }
    }

    if (showInfoSheet) {
        val post = posts[viewerState.currentIndex]
        ModalBottomSheet(
            onDismissRequest = { showInfoSheet = false },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Info & Actions", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        onSave(post)
                        showInfoSheet = false
                    }) {
                        Text("Save to Codex")
                    }
                    if (!post.pageUrl.isNullOrBlank()) {
                        TextButton(onClick = { onOpenInBrowser(post) }) {
                            Text("Open in browser")
                        }
                    }
                }

                Text("Tags", style = MaterialTheme.typography.titleSmall)
                post.canonicalTags.forEach { tag ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(tag, modifier = Modifier.weight(1f))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { onAddIncludeTag(tag) }) { Text("+") }
                            TextButton(onClick = { onAddExcludeTag(tag) }) { Text("-") }
                        }
                    }
                }

                TextButton(onClick = {
                    onGoToSearch()
                    showInfoSheet = false
                }) {
                    Text("Go to Search")
                }
            }
        }
    }
}

@Composable
private fun ViewerChrome(
    modifier: Modifier = Modifier,
    source: String,
    indexLabel: String,
    onBack: () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("$source • $indexLabel", style = MaterialTheme.typography.bodyLarge)
            IconButton(onClick = {}) {
                Icon(Icons.Default.Info, contentDescription = null, tint = Color.Transparent)
            }
        }
    }
}

@Composable
private fun ViewerActionsBar(
    modifier: Modifier = Modifier,
    onSave: () -> Unit,
    onInfo: () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onSave) {
                Icon(Icons.Default.Save, contentDescription = null)
                Text("Save", modifier = Modifier.padding(start = 8.dp))
            }
            TextButton(onClick = onInfo) {
                Icon(Icons.Default.Info, contentDescription = null)
                Text("Info", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}
