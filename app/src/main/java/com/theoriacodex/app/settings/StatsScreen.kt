package com.theoriacodex.app.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.theoriacodex.app.source.displayName
import com.theoriacodex.app.statistics.CodexPostStatistic
import com.theoriacodex.app.statistics.CodexStatisticsSummary
import com.theoriacodex.app.statistics.SourceStatistic
import com.theoriacodex.app.statistics.StatisticsSummary
import com.theoriacodex.app.statistics.TagStatistic
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.launch

// Validated against the Theoria surface with the dataviz palette checks (lightness, CVD, contrast).
private val BrowsingColor = Color(0xFF5A99DD)
private val WatchingColor = Color(0xFFCC689C)
private val CodexColor = Color(0xFFA58C3A)
private val OtherColor = Color(0xFF6F7A8C)

private data class TimeSegment(val label: String, val ms: Long, val color: Color)

private enum class PostSourceView(val label: String) { WATCHED("Watched"), SAVED("Saved") }
private enum class TagView(val label: String) { WATCHED("Watched"), SAVED("Saved") }
private enum class CodexPostView(val label: String) { OPENED("Opened"), VIDEOS("Videos"), IMAGES("Images") }

/** Lifetime totals and daily trends. A selected Codex replaces the list until Back. */
@Composable
internal fun StatsScreen(statistics: StatisticsSummary, onBack: () -> Unit) {
    var selectedCodexId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = statistics.codices.firstOrNull { it.codexId == selectedCodexId }
    if (selected != null) {
        BackHandler { selectedCodexId = null }
        CodexStatsDetail(selected) { selectedCodexId = null }
    } else {
        StatsOverview(statistics, onBack) { selectedCodexId = it }
    }
}

@Composable
private fun StatsOverview(statistics: StatisticsSummary, onBack: () -> Unit, onOpenCodex: (String) -> Unit) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val sections = listOf<Pair<String, @Composable () -> Unit>>(
        "Time" to { TimeSection(statistics) },
        "Posts" to { PostsSection(statistics) },
        "Searches" to { SearchesSection(statistics) },
        "Tags" to { TagsSection(statistics) },
        "Codices" to { CodicesSection(statistics.codices, onOpenCodex) },
    )
    val firstSectionIndex = 2
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            state = list,
            modifier = Modifier.widthIn(max = 800.dp).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "header") { SettingsHeader("Stats", onBack) }
            item(key = "chips") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(sections) { index, (label, _) ->
                        AssistChip(
                            onClick = { scope.launch { list.animateScrollToItem(firstSectionIndex + index) } },
                            label = { Text(label) },
                        )
                    }
                }
            }
            sections.forEach { (label, content) -> item(key = label) { Column { content() } } }
        }
    }
}

@Composable
private fun TimeSection(statistics: StatisticsSummary) {
    val categorized = statistics.browsingMs + statistics.watchingMs + statistics.codexMs
    StatsCard {
        StatsCaption("All-time in Theoria")
        StatsHeadline(formatStatsDuration(statistics.totalForegroundMs))
        StatsCaption("across ${statistics.appOpenCount} opens")
        TimeSplit(
            listOf(
                TimeSegment("Browsing", statistics.browsingMs, BrowsingColor),
                TimeSegment("Watching", statistics.watchingMs, WatchingColor),
                TimeSegment("In Codices", statistics.codexMs, CodexColor),
                // Foreground time also counts screens without a category, such as Settings.
                TimeSegment("Other", (statistics.totalForegroundMs - categorized).coerceAtLeast(0L), OtherColor),
            ),
        )
    }
    Box(Modifier.padding(top = 8.dp)) { TrendCard(statistics) }
}

@Composable
private fun TrendCard(statistics: StatisticsSummary) {
    var range by rememberSaveable { mutableStateOf(StatsTrendRange.WEEK) }
    val bars = remember(statistics.history, range) { statsTrendBars(statistics.history, LocalDate.now(), range) }
    val totalMs = bars.sumOf(StatsTrendBar::foregroundMs)
    val watched = bars.sumOf(StatsTrendBar::watchedPosts)
    StatsCard {
        StatsSegments(StatsTrendRange.entries, range, { it.label }) { range = it }
        if (statistics.history.isEmpty()) {
            StatsCaption("Your trends start today. Activity appears here day by day.")
            return@StatsCard
        }
        Text(formatStatsDuration(totalMs), style = MaterialTheme.typography.titleLarge)
        StatsCaption("in app · $watched posts watched")
        TrendBars(bars, range.label)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatsCaption(bars.first().label)
            StatsCaption(bars.last().label)
        }
    }
}

@Composable
private fun TrendBars(bars: List<StatsTrendBar>, rangeLabel: String) {
    val barColor = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    val peak = bars.maxOf(StatsTrendBar::foregroundMs).coerceAtLeast(1L)
    val description = "Time in app over $rangeLabel: " + bars.joinToString { bar ->
        "${bar.label} ${formatStatsDuration(bar.foregroundMs)}"
    }
    Canvas(Modifier.fillMaxWidth().height(96.dp).semantics { contentDescription = description }) {
        val gap = 2.dp.toPx()
        val width = (size.width - gap * (bars.size - 1)) / bars.size
        val minimum = 2.dp.toPx()
        bars.forEachIndexed { index, bar ->
            val value = bar.foregroundMs
            val height = if (value <= 0L) minimum else (size.height * value / peak).coerceAtLeast(minimum)
            drawRoundRect(
                color = if (value <= 0L) track else barColor,
                topLeft = Offset(index * (width + gap), size.height - height),
                size = Size(width, height),
                cornerRadius = CornerRadius(minOf(4.dp.toPx(), width / 2)),
            )
        }
    }
}

@Composable
private fun PostsSection(statistics: StatisticsSummary) {
    var view by rememberSaveable { mutableStateOf(PostSourceView.WATCHED) }
    StatsSectionTitle("Posts")
    StatTiles(
        "Watched" to statistics.watchedPostCount,
        "Saved" to statistics.savedPostCount,
        "Saved from For You" to statistics.forYouSaveCount,
        "Links copied" to statistics.postUrlCopyCount,
    )
    Box(Modifier.padding(top = 8.dp)) {
        StatsCard {
            StatsSegments(PostSourceView.entries, view, { it.label }) { view = it }
            SourceBars(if (view == PostSourceView.WATCHED) statistics.watchedSources else statistics.savedSources)
        }
    }
}

@Composable
private fun SearchesSection(statistics: StatisticsSummary) {
    StatsSectionTitle("Searches")
    StatTiles("Searches" to statistics.searchCount, "For You searches" to statistics.forYouSearchCount)
    Box(Modifier.padding(top = 8.dp)) {
        StatsCard { SourceBars(statistics.searchSources) }
    }
}

@Composable
private fun TagsSection(statistics: StatisticsSummary) {
    var view by rememberSaveable { mutableStateOf(TagView.WATCHED) }
    val rows: List<TagStatistic> = if (view == TagView.WATCHED) statistics.topWatchedTags else statistics.topSavedTags
    StatsSectionTitle("Top tags")
    StatsCard {
        StatsSegments(TagView.entries, view, { it.label }) { view = it }
        if (rows.isEmpty()) StatsCaption("No tags yet.")
        rows.forEach { row ->
            StatBar("${row.key.tag} · ${row.key.source.displayName()}", "${row.percentage}% · ${row.count}", row.percentage)
        }
    }
}

@Composable
private fun CodicesSection(codices: List<CodexStatisticsSummary>, onOpen: (String) -> Unit) {
    StatsSectionTitle("Codices")
    if (codices.isEmpty()) {
        StatsCaption("No Codices yet.")
        return
    }
    SettingsGroup(null) {
        codices.forEachIndexed { index, codex ->
            if (index > 0) SettingsRowDivider()
            SettingsRow(
                codex.name,
                "${codex.entries} opens · ${formatStatsDuration(codex.watchingMs)} watched",
                onClick = { onOpen(codex.codexId) },
            )
        }
    }
}

@Composable
private fun CodexStatsDetail(codex: CodexStatisticsSummary, onBack: () -> Unit) {
    var view by rememberSaveable(codex.codexId) { mutableStateOf(CodexPostView.OPENED) }
    SettingsPageColumn {
        SettingsHeader(codex.name, onBack)
        StatsCard {
            StatsCaption("Time inside")
            StatsHeadline(formatStatsDuration(codex.browsingMs + codex.watchingMs))
            TimeSplit(
                listOf(
                    TimeSegment("Browsing", codex.browsingMs, BrowsingColor),
                    TimeSegment("Watching", codex.watchingMs, WatchingColor),
                ),
            )
        }
        StatTiles("Times opened" to codex.entries, "Post opens" to codex.postsSeen)
        StatsSectionTitle("Top posts")
        StatsCard {
            StatsSegments(CodexPostView.entries, view, { it.label }) { view = it }
            val rows = when (view) {
                CodexPostView.OPENED -> codex.topOpened
                CodexPostView.VIDEOS -> codex.topVideos
                CodexPostView.IMAGES -> codex.topImages
            }
            if (rows.isEmpty()) StatsCaption("No activity yet.")
            rows.forEach { row -> TopPostRow(row, duration = view != CodexPostView.OPENED) }
        }
    }
}

/** Promoted posts carry a local thumbnail; posts without one yet stay label-only. */
@Composable
private fun TopPostRow(row: CodexPostStatistic, duration: Boolean) {
    val amount = if (duration) formatStatsDuration(row.value) else row.value.toString()
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        row.thumbnail?.let { path ->
            AsyncImage(
                model = File(path), contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }
        Box(Modifier.weight(1f)) { StatBar(row.label, "${row.percentage}% · $amount", row.percentage) }
    }
}

@Composable
private fun SourceBars(rows: List<SourceStatistic>) {
    if (rows.isEmpty()) StatsCaption("No activity yet.")
    rows.forEach { row -> StatBar(row.source.displayName(), "${row.percentage}% · ${row.count}", row.percentage) }
}

@Composable
private fun StatBar(label: String, value: String, percentage: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            StatsCaption(value)
        }
        LinearProgressIndicator(
            progress = { percentage.coerceIn(0, 100) / 100f },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            drawStopIndicator = {},
        )
    }
}

@Composable
private fun TimeSplit(segments: List<TimeSegment>) {
    val visible = segments.filter { it.ms > 0L }
    if (visible.isEmpty()) return
    Row(Modifier.fillMaxWidth().height(12.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        visible.forEach { segment ->
            Box(
                Modifier.weight(segment.ms.toFloat()).fillMaxHeight()
                    .clip(RoundedCornerShape(4.dp)).background(segment.color),
            )
        }
    }
    visible.forEach { segment ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(segment.color))
            Text(segment.label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            Text(formatStatsDuration(segment.ms), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun StatTiles(vararg tiles: Pair<String, Long>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.toList().chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (label, value) ->
                    Card(Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Text("%,d".format(value), style = MaterialTheme.typography.titleLarge)
                            StatsCaption(label)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun <T> StatsSegments(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                icon = {},
                label = { Text(label(option)) },
            )
        }
    }
}

@Composable
private fun StatsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun StatsHeadline(text: String) {
    Text(text, style = MaterialTheme.typography.displaySmall)
}

@Composable
private fun StatsSectionTitle(text: String) {
    Text(
        text,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun StatsCaption(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
