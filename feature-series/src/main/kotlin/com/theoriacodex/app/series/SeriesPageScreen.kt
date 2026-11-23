package com.theoriacodex.app.series

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.theoriacodex.app.media.copyTextToClipboard
import com.theoriacodex.app.ui.components.EmptyState
import com.theoriacodex.app.ui.components.LoadingState
import com.theoriacodex.app.ui.components.LocalPostMenuActions
import com.theoriacodex.app.ui.components.rememberQuickReturn
import com.theoriacodex.app.ui.undo.LocalUndoSlot
import com.theoriacodex.app.ui.undo.UndoAnchor
import com.theoriacodex.app.ui.undo.UndoLeaveEffect
import com.theoriacodex.app.ui.undo.UndoOffer
import com.theoriacodex.app.ui.undo.UndoPill
import com.theoriacodex.data.repository.StoredSeries
import com.theoriacodex.data.repository.StoredSeriesEntry
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesKind
import com.theoriacodex.domain.series.SeriesStatus
import kotlinx.coroutines.flow.collectLatest

/**
 * One series: blurred cover backdrop, cover and metadata, Follow / open-in-browser / Share actions, expandable
 * description with clickable genre pills, Continue button, entry search and sort/filter controls, and entry cards
 * with long-press read actions, undo support, and downloads. Entries are chapters or episodes by the series' kind;
 * episodes do not download.
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
    onSearchGenre: ((String) -> Unit)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val postActions = LocalPostMenuActions.current
    val undoAnchor = remember(state.stored?.series?.id) { UndoAnchor(SERIES_UNDO_SCOPE, state.stored?.series?.id?.toString()) }
    val offerBulkUndo = rememberBulkReadUndo(undoAnchor, viewModel)
    val toggleFollowed = rememberFollowToggle(viewModel)
    val listState = rememberLazyListState()
    var topBarVisible by rememberSaveable { mutableStateOf(true) }
    var optionsSheetOpen by rememberSaveable { mutableStateOf(false) }
    var coverLightboxOpen by rememberSaveable { mutableStateOf(false) }
    val scrolled by remember(listState) { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 48 } }
    LaunchedEffect(listState) { snapshotFlow { !listState.canScrollBackward }.collectLatest { atTop -> if (atTop) topBarVisible = true } }
    val quickReturn = rememberQuickReturn { revealed -> topBarVisible = revealed }

    Box(modifier.fillMaxSize().nestedScroll(quickReturn)) {
        SeriesPageBody(
            state = state,
            listState = listState,
            viewModel = viewModel,
            onToggleFollowed = toggleFollowed,
            onOpenInBrowser = { state.webUrl?.let(postActions.openInBrowser) },
            onShare = { state.webUrl?.let(postActions.share) },
            onOpenCover = { coverLightboxOpen = true },
            onCopyTitle = { copySeriesTitle(context, it) },
            onSearchGenre = onSearchGenre,
            onOpenEntry = onOpenEntry,
            onMarkPreviousRead = { viewModel.markPreviousRead(it, offerBulkUndo) },
            onOpenExtensions = onOpenExtensions,
            onOpenChapterOptions = { optionsSheetOpen = true },
            onClearQuery = { viewModel.setChapterQuery("") },
            onClearChapterFilters = { viewModel.resetChapterOptions(); viewModel.setChapterQuery("") },
        )
        SeriesPageTopBar(
            state = state,
            visible = topBarVisible,
            scrolled = scrolled,
            searchOpen = viewModel.chapterSearchOpen,
            searchQuery = viewModel.chapterSearchInput,
            canDownload = viewModel.canDownload,
            switchSource = switchSource,
            onBack = onBack,
            onOpenSearch = { viewModel.openChapterSearch(); topBarVisible = true },
            onCloseSearch = viewModel::closeChapterSearch,
            onQueryChange = viewModel::setChapterQuery,
            onRefresh = { viewModel.refresh() },
            onMarkAllRead = { viewModel.markAllRead(it, offerBulkUndo) },
            onDownload = viewModel::download,
            onDeleteDownloads = viewModel::deleteDownloads,
            onDownloadNewChapters = viewModel::setDownloadNewChapters,
            modifier = Modifier.align(Alignment.TopCenter),
        )
        UndoPill(undoAnchor, Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp, vertical = 16.dp))
        SeriesPageOverlays(state, viewModel, switchSource, optionsSheetOpen, coverLightboxOpen, { optionsSheetOpen = false }, { coverLightboxOpen = false }, onOpenSeries, onSwitched)
    }
}

@Composable
private fun rememberBulkReadUndo(anchor: UndoAnchor, viewModel: SeriesPageViewModel): (BulkReadUndo) -> Unit {
    val undoSlot = LocalUndoSlot.current
    val mounted = remember { booleanArrayOf(true) }
    DisposableEffect(Unit) {
        mounted[0] = true
        onDispose { mounted[0] = false }
    }
    UndoLeaveEffect(SERIES_UNDO_SCOPE)
    return { snapshot ->
        if (mounted[0]) {
            undoSlot.offer(undoSlot.scope, UndoOffer(snapshot.label, anchor) { viewModel.undoBulkRead(snapshot) })
        }
    }
}

private fun copySeriesTitle(context: android.content.Context, title: String) {
    if (copyTextToClipboard(context, "series_title", title)) {
        Toast.makeText(context, "Title copied", Toast.LENGTH_SHORT).show()
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
private fun SeriesPageOverlays(
    state: SeriesPageState,
    viewModel: SeriesPageViewModel,
    switchSource: SwitchSourceViewModel,
    optionsSheetOpen: Boolean,
    coverLightboxOpen: Boolean,
    onDismissOptions: () -> Unit,
    onDismissCover: () -> Unit,
    onOpenSeries: (SeriesId) -> Unit,
    onSwitched: (SeriesId) -> Unit,
) {
    if (optionsSheetOpen) {
        ChapterOptionsSheet(
            sort = state.chapterSort,
            descending = state.sortDescending,
            filter = state.chapterFilter,
            canDownload = viewModel.canDownload,
            words = state.kind.words,
            hasActiveOptions = state.hasActiveChapterOptions,
            onSelectSort = viewModel::setChapterSort,
            onSelectFilter = viewModel::setChapterFilter,
            onReset = viewModel::resetChapterOptions,
            onDismiss = onDismissOptions,
        )
    }
    if (coverLightboxOpen) {
        state.stored?.series?.let { series ->
            CoverLightboxDialog(series, onDismiss = onDismissCover)
        }
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
    onOpenCover: () -> Unit,
    onCopyTitle: (String) -> Unit,
    onSearchGenre: ((String) -> Unit)?,
    onOpenEntry: (String) -> Unit,
    onMarkPreviousRead: (String) -> Unit,
    onOpenExtensions: () -> Unit,
    onOpenChapterOptions: () -> Unit,
    onClearQuery: () -> Unit,
    onClearChapterFilters: () -> Unit,
) {
    val pullState = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = state.userRefreshing,
        onRefresh = { if (!state.sourceMissing) viewModel.refresh(userInitiated = true) },
        state = pullState,
        modifier = Modifier.fillMaxSize(),
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = pullState,
                isRefreshing = state.userRefreshing,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = SERIES_TOP_BAR_HEIGHT),
            )
        },
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = if (state.stored == null) SERIES_TOP_BAR_HEIGHT else 0.dp, bottom = 28.dp),
        ) {
            state.stored?.let { stored ->
                item("hero") {
                    SeriesHeroHeader(stored, state, onToggleFollowed, onOpenInBrowser, onShare, onOpenCover, onCopyTitle)
                }
                item("about") { SeriesAbout(stored.series, onSearchGenre) }
                state.continueWith?.let { entry ->
                    item("continue") {
                        ContinueReadingButton(
                            label = continueLabel(state.started, entry, state.kind),
                            enabled = state.canOpen(entry.entry.address),
                            onClick = { onOpenEntry(entry.entry.address) },
                        )
                    }
                }
            }
            statusRows(state, { viewModel.refresh() }, onOpenExtensions)
            chapterList(state, viewModel, onOpenEntry, onMarkPreviousRead, onOpenChapterOptions, onClearQuery, onClearChapterFilters)
        }
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
                message = failure.message,
                source = state.source?.key,
                sourceName = state.sourceName,
                onRetry = onRefresh.takeIf { failure.retryable },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                compact = state.entries.isNotEmpty(),
            )
        }
        state.refreshing && state.entries.isEmpty() -> item("loading") { LoadingState() }
        state.entries.isEmpty() -> item("empty") { EmptyState(state.kind.words.none) }
    }
}

/** The entry count with sort/filter controls, active filter pills, and every visible entry card. */
private fun LazyListScope.chapterList(
    state: SeriesPageState,
    viewModel: SeriesPageViewModel,
    onOpenEntry: (String) -> Unit,
    onMarkPreviousRead: (String) -> Unit,
    onOpenChapterOptions: () -> Unit,
    onClearQuery: () -> Unit,
    onClearChapterFilters: () -> Unit,
) {
    if (state.entries.isEmpty()) return
    val downloads = viewModel.canDownload
    val maxUnreadSourceOrder = state.entries.lastOrNull { !it.read }?.sourceOrder ?: -1
    item("chapters") {
        ChapterListHeader(
            words = state.kind.words,
            totalCount = state.entries.size,
            visibleCount = state.visibleEntries.size,
            filterActive = state.hasActiveChapterOptions,
            onOpenOptions = onOpenChapterOptions,
        )
    }
    item("active-chapter-pills") {
        ActiveChapterOptionPills(
            state = state,
            onClearFilter = { viewModel.setChapterFilter(ChapterFilter.ALL) },
            onClearSort = viewModel::resetChapterSort,
            onClearQuery = onClearQuery,
        )
    }
    if (state.visibleEntries.isEmpty()) {
        item("filtered-empty") { EmptyFilteredChapters(state.kind.words, onClearChapterFilters) }
        return
    }
    items(state.visibleEntries, key = { it.entry.address }) { entry ->
        val address = entry.entry.address
        val download = state.downloads[address]
        val canDownloadEntry = downloads && (download != null || !state.sourceMissing)
        EntryRow(
            entry = entry,
            kind = state.kind,
            enabled = state.canOpen(address),
            hasPreviousUnread = maxUnreadSourceOrder > entry.sourceOrder,
            download = download,
            canToggleDownload = canDownloadEntry,
            onClick = { onOpenEntry(address) },
            onToggleRead = { viewModel.setEntryRead(address, !entry.read) },
            onMarkPreviousRead = { onMarkPreviousRead(address) },
            onToggleDownload = { viewModel.toggleDownload(address) },
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
    onOpenCover: () -> Unit,
    onCopyTitle: (String) -> Unit,
) {
    Box(Modifier.fillMaxWidth()) {
        HeroBackdrop(stored.series, Modifier.matchParentSize())
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = SERIES_TOP_BAR_HEIGHT + 8.dp, start = 16.dp, end = 16.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SeriesCover(
                    stored.series,
                    Modifier
                        .width(HERO_COVER_WIDTH)
                        .testTag(HERO_COVER_TAG)
                        .clickable(role = Role.Image, onClickLabel = "View cover", onClick = onOpenCover),
                )
                HeroMetadata(stored.series, state.sourceName, onCopyTitle, Modifier.weight(1f))
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
private fun HeroMetadata(series: Series, sourceName: String, onCopyTitle: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = series.title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .pointerInput(series.title) { detectTapGestures(onLongPress = { onCopyTitle(series.title) }) }
                .semantics { onLongClick(label = "Copy title") { onCopyTitle(series.title); true } },
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
private fun SeriesAbout(series: Series, onSearchGenre: ((String) -> Unit)?) {
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
            SeriesGenreChips(series.genres, onSearchGenre)
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
private fun SeriesGenreChips(genres: List<String>, onSearchGenre: ((String) -> Unit)?) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        genres.forEach { genre ->
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = if (onSearchGenre != null) Modifier.clickable(role = Role.Button) { onSearchGenre(genre) } else Modifier,
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

/** Says the series' extension cannot be used, which is why its entries cannot be opened, and leads to where to fix it. */
@Composable
private fun MissingSourceRow(onOpenExtensions: () -> Unit) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Extension unavailable", color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onOpenExtensions) { Text(EXTENSIONS_TITLE) }
    }
}

/** "Continue reading Chapter 4" or "Start watching", by the series' [kind]. */
internal fun continueLabel(started: Boolean, entry: StoredSeriesEntry, kind: SeriesKind): String =
    if (started) "Continue ${kind.words.doing} ${entry.entry.name}" else "Start ${kind.words.doing}"

private val SeriesStatus.label: String?
    get() = when (this) {
        SeriesStatus.UNKNOWN -> null
        SeriesStatus.ONGOING -> "Ongoing"
        SeriesStatus.COMPLETED -> "Completed"
        SeriesStatus.LICENSED -> "Licensed"
        SeriesStatus.PUBLISHING_FINISHED -> "Finished"
        SeriesStatus.CANCELLED -> "Cancelled"
        SeriesStatus.ON_HIATUS -> "Hiatus"
        SeriesStatus.UPCOMING -> "Upcoming"
    }

private const val SERIES_UNDO_SCOPE = "series-page"
internal const val HERO_COVER_TAG = "Series hero cover"
private val HERO_COVER_WIDTH = 108.dp
private val HERO_BLUR_RADIUS = 20.dp
private val ACTION_BUTTON_HEIGHT = 44.dp
private const val DESCRIPTION_LINES = 3
