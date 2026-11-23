package com.theoriacodex.app.series

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.ArrowCircleDown
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.theoriacodex.app.ui.components.EmptyState
import com.theoriacodex.app.ui.components.LoadingState
import com.theoriacodex.app.ui.components.LocalPostMenuActions
import com.theoriacodex.app.ui.components.rememberQuickReturn
import com.theoriacodex.data.repository.ChapterDownload
import com.theoriacodex.data.repository.StoredSeries
import com.theoriacodex.data.repository.StoredSeriesEntry
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesStatus
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.flow.collectLatest

/**
 * One series: blurred cover backdrop, cover and metadata, Follow / open-in-browser / Share actions, expandable
 * description with genre pills, Continue button, chapter search and sort/filter controls, and chapter cards with
 * read progress and downloads.
 */
@Composable
fun SeriesPageScreen(
    viewModel: SeriesPageViewModel,
    onBack: () -> Unit,
    onOpenEntry: (String) -> Unit,
    onOpenExtensions: () -> Unit,
    switchSource: SwitchSourceViewModel,
    onOpenSeries: (SeriesId) -> Unit,
    onSwitched: (SeriesId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val postActions = LocalPostMenuActions.current
    val toggleFollowed = rememberFollowToggle(viewModel)
    val listState = rememberLazyListState()
    var topBarVisible by rememberSaveable { mutableStateOf(true) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var optionsSheetOpen by rememberSaveable { mutableStateOf(false) }
    val scrolled by remember(listState) {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 48 }
    }
    LaunchedEffect(listState) {
        snapshotFlow { !listState.canScrollBackward }.collectLatest { atTop -> if (atTop) topBarVisible = true }
    }
    val quickReturn = rememberQuickReturn { revealed -> topBarVisible = revealed }

    Box(modifier.fillMaxSize().nestedScroll(quickReturn)) {
        SeriesPageBody(
            state = state,
            listState = listState,
            viewModel = viewModel,
            onToggleFollowed = toggleFollowed,
            onOpenInBrowser = { state.webUrl?.let(postActions.openInBrowser) },
            onShare = { state.webUrl?.let(postActions.share) },
            onOpenEntry = onOpenEntry,
            onOpenExtensions = onOpenExtensions,
            onOpenChapterOptions = { optionsSheetOpen = true },
            onClearChapterFilters = {
                viewModel.resetChapterOptions()
                viewModel.setChapterQuery("")
            },
        )
        SeriesPageTopBar(
            state = state,
            visible = topBarVisible,
            scrolled = scrolled,
            searchOpen = searchOpen,
            canDownload = viewModel.canDownload,
            switchSource = switchSource,
            onBack = onBack,
            onOpenSearch = { searchOpen = true; topBarVisible = true },
            onCloseSearch = { searchOpen = false; viewModel.setChapterQuery("") },
            onQueryChange = viewModel::setChapterQuery,
            onRefresh = viewModel::refresh,
            onMarkAllRead = viewModel::markAllRead,
            onDownload = viewModel::download,
            onDeleteDownloads = viewModel::deleteDownloads,
            onDownloadNewChapters = viewModel::setDownloadNewChapters,
            modifier = Modifier.align(Alignment.TopCenter),
        )
        SeriesPageSheets(state, viewModel, switchSource, optionsSheetOpen, { optionsSheetOpen = false }, onOpenSeries, onSwitched)
    }
}

@Composable
private fun rememberFollowToggle(viewModel: SeriesPageViewModel): () -> Unit {
    val context = LocalContext.current
    val askToNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    return {
        viewModel.toggleFollowed {
            val permission = Manifest.permission.POST_NOTIFICATIONS
            if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) askToNotify.launch(permission)
        }
    }
}

@Composable
private fun SeriesPageSheets(
    state: SeriesPageState,
    viewModel: SeriesPageViewModel,
    switchSource: SwitchSourceViewModel,
    optionsSheetOpen: Boolean,
    onDismissOptions: () -> Unit,
    onOpenSeries: (SeriesId) -> Unit,
    onSwitched: (SeriesId) -> Unit,
) {
    if (optionsSheetOpen) {
        ChapterOptionsSheet(
            sort = state.chapterSort,
            descending = state.sortDescending,
            filter = state.chapterFilter,
            canDownload = viewModel.canDownload,
            hasActiveOptions = state.hasActiveChapterOptions,
            onSelectSort = viewModel::setChapterSort,
            onSelectFilter = viewModel::setChapterFilter,
            onReset = viewModel::resetChapterOptions,
            onDismiss = onDismissOptions,
        )
    }
    SwitchSourceSheet(switchSource, onOpenSeries, onSwitched)
}

@Composable
private fun SeriesPageBody(
    state: SeriesPageState,
    listState: LazyListState,
    viewModel: SeriesPageViewModel,
    onToggleFollowed: () -> Unit,
    onOpenInBrowser: () -> Unit,
    onShare: () -> Unit,
    onOpenEntry: (String) -> Unit,
    onOpenExtensions: () -> Unit,
    onOpenChapterOptions: () -> Unit,
    onClearChapterFilters: () -> Unit,
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = if (state.stored == null) SERIES_TOP_BAR_HEIGHT else 0.dp, bottom = 28.dp),
    ) {
        state.stored?.let { stored ->
            item("hero") {
                SeriesHeroHeader(stored, state, onToggleFollowed, onOpenInBrowser, onShare)
            }
            item("about") { SeriesAbout(stored.series) }
            state.continueWith?.let { entry ->
                item("continue") {
                    ContinueReadingButton(
                        label = continueLabel(state.started, entry),
                        enabled = state.canOpen(entry.entry.address),
                        onClick = { onOpenEntry(entry.entry.address) },
                    )
                }
            }
        }
        statusRows(state, viewModel::refresh, onOpenExtensions)
        chapterList(state, viewModel, onOpenEntry, onOpenChapterOptions, onClearChapterFilters)
    }
}

private fun LazyListScope.statusRows(
    state: SeriesPageState,
    onRefresh: () -> Unit,
    onOpenExtensions: () -> Unit,
) {
    val failure = state.failure
    when {
        state.sourceMissing -> item("missing") { MissingSourceRow(onOpenExtensions) }
        failure != null -> item("failure") {
            SourceLoadFailure(
                message = failure,
                source = state.source?.key,
                sourceName = state.sourceName,
                onRetry = onRefresh,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                compact = state.entries.isNotEmpty(),
            )
        }
        state.refreshing && state.entries.isEmpty() -> item("loading") { LoadingState() }
        state.entries.isEmpty() -> item("empty") { EmptyState("No chapters") }
    }
}

/** The chapter count with the sort/filter button, then every visible chapter card. */
private fun LazyListScope.chapterList(
    state: SeriesPageState,
    viewModel: SeriesPageViewModel,
    onOpenEntry: (String) -> Unit,
    onOpenChapterOptions: () -> Unit,
    onClearChapterFilters: () -> Unit,
) {
    if (state.entries.isEmpty()) return
    val downloads = viewModel.canDownload
    item("chapters") {
        ChapterListHeader(
            totalCount = state.entries.size,
            visibleCount = state.visibleEntries.size,
            filterActive = state.hasActiveChapterOptions,
            onOpenOptions = onOpenChapterOptions,
        )
    }
    if (state.visibleEntries.isEmpty()) {
        item("filtered-empty") { EmptyFilteredChapters(onClearChapterFilters) }
        return
    }
    items(state.visibleEntries, key = { it.entry.address }) { entry ->
        val address = entry.entry.address
        val download = state.downloads[address]
        EntryRow(
            entry = entry,
            enabled = state.canOpen(address),
            onClick = { onOpenEntry(address) },
            // Without its source a chapter cannot download; one queued or on the device can still be cancelled or deleted.
            trailing = if (downloads && (download != null || !state.sourceMissing)) {
                { DownloadMark(download) { viewModel.toggleDownload(address) } }
            } else null,
        )
    }
}

/** Hero section: blurred cover backdrop behind the floating top bar, cover + metadata, and action buttons. */
@Composable
private fun SeriesHeroHeader(
    stored: StoredSeries,
    state: SeriesPageState,
    onToggleFollowed: () -> Unit,
    onOpenInBrowser: () -> Unit,
    onShare: () -> Unit,
) {
    CompositionLocalProvider(LocalSeriesSources provides listOfNotNull(state.source)) {
        Box(Modifier.fillMaxWidth()) {
            HeroBackdrop(stored.series, Modifier.matchParentSize())
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = SERIES_TOP_BAR_HEIGHT + 8.dp, start = 16.dp, end = 16.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    SeriesCover(stored.series, Modifier.width(HERO_COVER_WIDTH))
                    HeroMetadata(stored.series, state.sourceName, Modifier.weight(1f))
                }
                HeroActionRow(
                    followed = stored.followed,
                    hasWebUrl = state.webUrl != null,
                    onToggleFollowed = onToggleFollowed,
                    onOpenInBrowser = onOpenInBrowser,
                    onShare = onShare,
                )
            }
        }
    }
}

@Composable
private fun HeroBackdrop(series: Series, modifier: Modifier = Modifier) {
    val background = MaterialTheme.colorScheme.background
    val request = rememberSeriesCoverRequest(series)
    val scrim = remember(background) {
        Brush.verticalGradient(
            0f to background.copy(alpha = 0.42f),
            0.55f to background.copy(alpha = 0.72f),
            1f to background,
        )
    }
    Box(modifier) {
        if (request != null) {
            AsyncImage(
                model = request,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().blur(HERO_BLUR_RADIUS),
                contentScale = ContentScale.Crop,
            )
        }
        Box(Modifier.fillMaxSize().background(scrim))
    }
}

@Composable
private fun HeroMetadata(series: Series, sourceName: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = series.title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        series.author?.let { author ->
            Text(
                text = author,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        series.status.label?.let { status ->
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (sourceName.isNotBlank()) {
            Text(
                text = sourceName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun HeroActionRow(
    followed: Boolean,
    hasWebUrl: Boolean,
    onToggleFollowed: () -> Unit,
    onOpenInBrowser: () -> Unit,
    onShare: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        FollowActionButton(followed, onToggleFollowed)
        if (hasWebUrl) {
            HeroCircleAction(Icons.Default.Public, "Open in browser", onOpenInBrowser)
            HeroCircleAction(Icons.Default.Share, "Share", onShare)
        }
    }
}

@Composable
private fun FollowActionButton(followed: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (followed) colors.secondaryContainer else colors.surfaceContainerHigh,
        contentColor = if (followed) colors.onSecondaryContainer else colors.onSurface,
        modifier = Modifier.height(ACTION_BUTTON_HEIGHT).semantics { selected = followed },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (followed) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = if (followed) "Following" else "Follow",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun HeroCircleAction(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.7f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.size(ACTION_BUTTON_HEIGHT),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * Expandable synopsis and genres: 3 lines with a bottom-right "More" fade when collapsed; full synopsis, wrapped
 * genre pills, and "Less" when expanded.
 */
@Composable
private fun SeriesAbout(series: Series) {
    val description = series.description
    val hasGenres = series.genres.isNotEmpty()
    if (description == null && !hasGenres) return
    var expanded by rememberSaveable(series.id) { mutableStateOf(false) }
    var overflowed by remember(description) { mutableStateOf(false) }
    val expandable = overflowed || hasGenres || expanded
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable(enabled = expandable, role = Role.Button) { expanded = !expanded }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("About this series", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        if (description != null) {
            AboutDescriptionBlock(
                description = description,
                expanded = expanded,
                showMoreAffordance = !expanded && (overflowed || hasGenres),
                onOverflowDetected = { overflowed = it },
            )
        }
        if (expanded && hasGenres) {
            SeriesGenreChips(series.genres)
        }
        if (expanded || (description == null && hasGenres)) {
            AboutExpandToggle(expanded = expanded, modifier = Modifier.align(Alignment.End))
        }
    }
}

@Composable
private fun AboutDescriptionBlock(
    description: String,
    expanded: Boolean,
    showMoreAffordance: Boolean,
    onOverflowDetected: (Boolean) -> Unit,
) {
    val background = MaterialTheme.colorScheme.background
    Box(Modifier.fillMaxWidth()) {
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else DESCRIPTION_LINES,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) onOverflowDetected(it.hasVisualOverflow) },
        )
        if (showMoreAffordance) {
            val fade = remember(background) {
                Brush.horizontalGradient(0f to Color.Transparent, 0.35f to background, 1f to background)
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .background(fade)
                    .padding(start = 28.dp),
            ) {
                AboutExpandToggle(expanded = false)
            }
        }
    }
}

@Composable
private fun AboutExpandToggle(expanded: Boolean, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = if (expanded) "Less" else "More",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Icon(
            imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(18.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeriesGenreChips(genres: List<String>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        genres.forEach { genre ->
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Text(
                    text = genre,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun ContinueReadingButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .height(48.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Says the series' extension cannot be used, which is why its chapters cannot be opened, and leads to where to fix it. */
@Composable
private fun MissingSourceRow(onOpenExtensions: () -> Unit) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Extension unavailable", color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onOpenExtensions) { Text(EXTENSIONS_TITLE) }
    }
}

@Composable
private fun ChapterListHeader(
    totalCount: Int,
    visibleCount: Int,
    filterActive: Boolean,
    onOpenOptions: () -> Unit,
) {
    val label = if (visibleCount == totalCount) chapterCount(totalCount) else "$visibleCount of ${chapterCount(totalCount)}"
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        IconButton(onClick = onOpenOptions) {
            Icon(
                imageVector = Icons.Default.FilterList,
                contentDescription = "Sort and filter chapters",
                tint = if (filterActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyFilteredChapters(onClear: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("No matching chapters", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onClear) { Text("Clear filters") }
    }
}

/** A chapter card: title, upload date / progress line, and optional download mark. Read chapters fade. */
@Composable
private fun EntryRow(entry: StoredSeriesEntry, enabled: Boolean, onClick: () -> Unit, trailing: (@Composable () -> Unit)?) {
    Surface(
        shape = CHAPTER_CARD_SHAPE,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp)
            .alpha(if (entry.read) READ_ALPHA else 1f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onClick)
                .padding(start = 16.dp, end = if (trailing == null) 16.dp else 6.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = entry.entry.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                entryDetail(entry)?.let { detail ->
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(4.dp))
                trailing()
            }
        }
    }
}

/** A chapter row's download state, which a tap moves on: download, stop the download, or delete it from the device. */
@Composable
private fun DownloadMark(download: ChapterDownload?, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        when (download) {
            null -> Icon(
                Icons.Outlined.ArrowCircleDown,
                contentDescription = "Download",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            is ChapterDownload.Queued -> Box(contentAlignment = Alignment.Center) {
                val ring = Modifier.size(DOWNLOAD_RING)
                val progress = download.progress
                if (progress == null) CircularProgressIndicator(ring, strokeWidth = 2.dp)
                else CircularProgressIndicator({ progress }, ring, strokeWidth = 2.dp)
                Icon(Icons.Default.Close, contentDescription = "Cancel download", Modifier.size(DOWNLOAD_STOP))
            }
            ChapterDownload.OnDevice -> Icon(
                Icons.Filled.CheckCircle,
                contentDescription = "Delete download",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

internal fun continueLabel(started: Boolean, entry: StoredSeriesEntry): String =
    if (started) "Continue reading ${entry.entry.name}" else "Start reading"

internal fun chapterCount(count: Int): String = if (count == 1) "1 chapter" else "$count chapters"

private val SeriesStatus.label: String?
    get() = when (this) {
        SeriesStatus.UNKNOWN -> null
        SeriesStatus.ONGOING -> "Ongoing"
        SeriesStatus.COMPLETED -> "Completed"
        SeriesStatus.LICENSED -> "Licensed"
        SeriesStatus.PUBLISHING_FINISHED -> "Finished"
        SeriesStatus.CANCELLED -> "Cancelled"
        SeriesStatus.ON_HIATUS -> "Hiatus"
    }

/** Date, reading progress, or "Read" status joined cleanly on one subtitle line. */
internal fun entryDetail(entry: StoredSeriesEntry): String? {
    val date = entry.entry.uploadedAtEpochMs?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) }
    val status = when {
        entry.read -> "Read"
        entry.lastPage > 0 -> entry.pageCount?.let { "Page ${entry.lastPage} of $it" } ?: "Page ${entry.lastPage}"
        else -> null
    }
    return listOfNotNull(date, status).joinToString(" · ").takeIf(String::isNotEmpty)
}

private val HERO_COVER_WIDTH = 108.dp
private val HERO_BLUR_RADIUS = 20.dp
private val ACTION_BUTTON_HEIGHT = 44.dp
private val CHAPTER_CARD_SHAPE = RoundedCornerShape(12.dp)
private val DOWNLOAD_RING = 22.dp
private val DOWNLOAD_STOP = 12.dp
private const val DESCRIPTION_LINES = 3
private const val READ_ALPHA = 0.5f
