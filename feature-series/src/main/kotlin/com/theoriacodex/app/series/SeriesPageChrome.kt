package com.theoriacodex.app.series

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.theoriacodex.app.ui.motion.TheoriaMotion
import com.theoriacodex.app.ui.components.SortOption
import com.theoriacodex.data.repository.DownloadChoice
import com.theoriacodex.data.repository.SeriesDownloads
import com.theoriacodex.domain.model.SortChoice
import com.theoriacodex.domain.model.SortDirection
import com.theoriacodex.domain.series.Series

@Composable
internal fun SeriesPageTopBar(
    state: SeriesPageState,
    visible: Boolean,
    scrolled: Boolean,
    searchOpen: Boolean,
    searchQuery: String,
    canDownload: Boolean,
    switchSource: SwitchSourceViewModel,
    onBack: () -> Unit,
    onOpenSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    onQueryChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onMarkAllRead: (Boolean) -> Unit,
    onDownload: (DownloadChoice) -> Unit,
    onDeleteDownloads: () -> Unit,
    onDownloadNewChapters: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(enabled = searchOpen, onBack = onCloseSearch)
    val containerColor by animateColorAsState(
        targetValue = if (scrolled || searchOpen) MaterialTheme.colorScheme.surface.copy(alpha = 0.96f) else Color.Transparent,
        animationSpec = tween(TheoriaMotion.SHORT_MS),
        label = "seriesTopBarContainer",
    )
    AnimatedVisibility(
        visible = visible || searchOpen,
        modifier = modifier.fillMaxWidth(),
        enter = slideInVertically(tween(TheoriaMotion.MEDIUM_MS)) { -it } + fadeIn(tween(TheoriaMotion.SHORT_MS)),
        exit = slideOutVertically(tween(TheoriaMotion.MEDIUM_MS)) { -it } + fadeOut(tween(TheoriaMotion.SHORT_MS)),
    ) {
        Surface(color = containerColor, modifier = Modifier.fillMaxWidth().testTag(SERIES_TOP_BAR_TAG)) {
            Column(Modifier.fillMaxWidth()) {
                if (searchOpen) {
                    SeriesTopBarSearchRow(
                        words = state.kind.words,
                        query = searchQuery,
                        onQueryChange = onQueryChange,
                        onClose = onCloseSearch,
                    )
                } else {
                    SeriesTopBarNormalRow(
                        state = state,
                        showTitle = scrolled,
                        canDownload = canDownload,
                        switchSource = switchSource,
                        onBack = onBack,
                        onOpenSearch = onOpenSearch,
                        onRefresh = onRefresh,
                        onMarkAllRead = onMarkAllRead,
                        onDownload = onDownload,
                        onDeleteDownloads = onDeleteDownloads,
                        onDownloadNewChapters = onDownloadNewChapters,
                    )
                }
                if (state.refreshing && !state.userRefreshing && state.entries.isNotEmpty()) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
                }
            }
        }
    }
}

@Composable
private fun SeriesTopBarNormalRow(
    state: SeriesPageState,
    showTitle: Boolean,
    canDownload: Boolean,
    switchSource: SwitchSourceViewModel,
    onBack: () -> Unit,
    onOpenSearch: () -> Unit,
    onRefresh: () -> Unit,
    onMarkAllRead: (Boolean) -> Unit,
    onDownload: (DownloadChoice) -> Unit,
    onDeleteDownloads: () -> Unit,
    onDownloadNewChapters: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = SERIES_TOP_BAR_HEIGHT).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Box(Modifier.weight(1f).padding(horizontal = 4.dp)) {
            if (showTitle) {
                state.stored?.series?.title?.let { title ->
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        IconButton(onClick = onOpenSearch) {
            Icon(Icons.Default.Search, contentDescription = "Search ${state.kind.words.many}")
        }
        if (canDownload) {
            SeriesDownloadMenu(state, onDownload, onDeleteDownloads, onDownloadNewChapters)
        }
        state.stored?.let { stored ->
            SeriesOverflowMenu(
                state = state,
                switchSource = switchSource,
                onMarkAllRead = onMarkAllRead,
                onRefresh = onRefresh,
                onSwitchSource = { switchSource.open(stored.series.title) },
            )
        }
    }
}

@Composable
private fun SeriesTopBarSearchRow(
    words: EntryWords,
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
) {
    val focus = LocalFocusManager.current
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        requester.requestFocus()
    }
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = SERIES_TOP_BAR_HEIGHT).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = {
            focus.clearFocus()
            onClose()
        }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close ${words.one} search")
        }
        Box(Modifier.weight(1f).padding(horizontal = 4.dp), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(
                    text = "Filter ${words.many}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                modifier = Modifier.fillMaxWidth().focusRequester(requester).testTag(CHAPTER_SEARCH_INPUT_TAG),
            )
        }
        if (query.isNotEmpty()) {
            IconButton(onClick = { onQueryChange("") }) {
                Icon(Icons.Default.Close, contentDescription = "Clear ${words.one} filter")
            }
        }
    }
}

@Composable
private fun SeriesDownloadMenu(
    state: SeriesPageState,
    onDownload: (DownloadChoice) -> Unit,
    onDeleteDownloads: () -> Unit,
    onDownloadNewChapters: (Boolean) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Outlined.Download, contentDescription = "Download")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            fun choose(action: () -> Unit) {
                open = false
                action()
            }
            if (!state.sourceMissing) {
                DOWNLOAD_CHOICES.forEach { (choice, label) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { choose { onDownload(choice) } })
                }
            }
            DropdownMenuItem(
                text = { Text("Delete downloads") },
                enabled = state.downloads.isNotEmpty(),
                onClick = { choose(onDeleteDownloads) },
            )
            val stored = state.stored
            if (stored?.followed == true) {
                HorizontalDivider()
                DownloadNewChaptersItem(stored.downloadNewChapters) { choose { onDownloadNewChapters(it) } }
            }
        }
    }
}

@Composable
private fun SeriesOverflowMenu(
    state: SeriesPageState,
    switchSource: SwitchSourceViewModel,
    onMarkAllRead: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onSwitchSource: () -> Unit,
) {
    val canSwitchSource = switchSource.state.collectAsStateWithLifecycle().value.others.isNotEmpty()
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "More options")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            fun choose(action: () -> Unit) {
                open = false
                action()
            }
            val words = state.kind.words
            DropdownMenuItem(
                text = { Text("Mark all as ${words.done}") },
                enabled = state.entries.any { !it.read },
                onClick = { choose { onMarkAllRead(true) } },
            )
            DropdownMenuItem(
                text = { Text("Mark all as ${words.notDone}") },
                enabled = state.entries.any { it.read || it.position > 0 },
                onClick = { choose { onMarkAllRead(false) } },
            )
            DropdownMenuItem(
                text = { Text("Refresh") },
                enabled = !state.sourceMissing && !state.refreshing,
                onClick = { choose(onRefresh) },
            )
            if (canSwitchSource) {
                DropdownMenuItem(
                    text = { Text(SWITCH_SOURCE) },
                    onClick = { choose(onSwitchSource) },
                )
            }
        }
    }
}

@Composable
private fun DownloadNewChaptersItem(on: Boolean, onChange: (Boolean) -> Unit) {
    DropdownMenuItem(
        text = { Text("Download new chapters") },
        trailingIcon = { if (on) Icon(Icons.Default.Check, contentDescription = null) },
        onClick = { onChange(!on) },
        modifier = Modifier.semantics { toggleableState = ToggleableState(on) },
    )
}

@Composable
internal fun ChapterOptionsSheet(
    sort: ChapterSort,
    descending: Boolean,
    filter: ChapterFilter,
    canDownload: Boolean,
    words: EntryWords,
    hasActiveOptions: Boolean,
    onSelectSort: (ChapterSort) -> Unit,
    onSelectFilter: (ChapterFilter) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    SortFilterSheet(
        filters = buildList {
            add(SortOption(ChapterFilter.ALL, "All"))
            add(SortOption(ChapterFilter.UNREAD, words.notDone.capitalized()))
            if (canDownload) add(SortOption(ChapterFilter.DOWNLOADED, "Downloaded"))
        },
        filter = filter,
        onFilter = onSelectFilter,
        sorts = ChapterSort.entries.map { SortOption(it, it.label(words)) },
        sort = SortChoice(sort, if (descending) SortDirection.DESCENDING else SortDirection.ASCENDING),
        onSort = onSelectSort,
        onReset = onReset.takeIf { hasActiveOptions },
        onDismiss = onDismiss,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ActiveChapterOptionPills(
    state: SeriesPageState,
    onClearFilter: () -> Unit,
    onClearSort: () -> Unit,
    onClearQuery: () -> Unit,
) {
    val words = state.kind.words
    val filterLabel = when (state.chapterFilter) {
        ChapterFilter.ALL -> null
        ChapterFilter.UNREAD -> words.notDone.capitalized()
        ChapterFilter.DOWNLOADED -> "Downloaded"
    }
    val sortLabel = if (state.chapterSort != ChapterSort.SOURCE_ORDER || !state.sortDescending) {
        "${state.chapterSort.label(words)} ${if (state.sortDescending) "↓" else "↑"}"
    } else null
    val queryLabel = state.chapterQuery.trim().takeIf(String::isNotEmpty)?.let { "\"$it\"" }
    if (filterLabel == null && sortLabel == null && queryLabel == null) return
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        filterLabel?.let { ActiveOptionChip(it, "Clear $it filter", onClearFilter) }
        sortLabel?.let { ActiveOptionChip(it, "Reset ${words.one} sort", onClearSort) }
        queryLabel?.let { ActiveOptionChip(it, "Clear ${words.one} search", onClearQuery) }
    }
}

@Composable
private fun ActiveOptionChip(label: String, clearDescription: String, onClear: () -> Unit) {
    Surface(
        onClick = onClear,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Default.Close, contentDescription = clearDescription, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
internal fun CoverLightboxDialog(series: Series, onDismiss: () -> Unit) {
    val request = rememberSeriesCoverRequest(series, targetWidthPx = FULL_COVER_WIDTH_PX, targetHeightPx = FULL_COVER_HEIGHT_PX)
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
                .clickable(onClick = onDismiss)
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val nextScale = (scale * zoom).coerceIn(1f, 4f)
                        scale = nextScale
                        offset = if (nextScale > 1f) offset + pan else Offset.Zero
                    }
                }
                .testTag(COVER_LIGHTBOX_TAG),
            contentAlignment = Alignment.Center,
        ) {
            if (request != null) {
                AsyncImage(
                    model = request,
                    contentDescription = series.title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y,
                        ),
                )
            }
            Surface(
                onClick = onDismiss,
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.6f),
                contentColor = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Close, contentDescription = "Close cover", modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}

private val DOWNLOAD_CHOICES = listOf(
    DownloadChoice.NEXT_UNREAD to "Next ${SeriesDownloads.NEXT_UNREAD_COUNT} unread",
    DownloadChoice.ALL_UNREAD to "All unread",
    DownloadChoice.ALL to "All",
)

private fun ChapterSort.label(words: EntryWords): String = when (this) {
    ChapterSort.SOURCE_ORDER -> "Source order"
    ChapterSort.CHAPTER_NUMBER -> "${words.one.capitalized()} number"
    ChapterSort.UPLOAD_DATE -> "Upload date"
}

internal val SERIES_TOP_BAR_HEIGHT = 56.dp
internal const val SERIES_TOP_BAR_TAG = "Series page top bar"
internal const val CHAPTER_SEARCH_INPUT_TAG = "Chapter search input"
internal const val COVER_LIGHTBOX_TAG = "Series cover lightbox"
