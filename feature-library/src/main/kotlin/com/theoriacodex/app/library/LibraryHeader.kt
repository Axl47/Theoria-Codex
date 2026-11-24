package com.theoriacodex.app.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

enum class LibrarySection(val label: String) {
    Codices("Codices"),
    Saved("Saved"),
    History("History"),
    ;

    companion object {
        fun named(name: String?): LibrarySection? = entries.firstOrNull { it.name == name }
    }
}

typealias LibrarySectionHeader = @Composable (actions: @Composable RowScope.() -> Unit) -> Unit

val BareSectionHeader: LibrarySectionHeader = { actions ->
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
        content = actions,
    )
}

@Composable
fun LibraryHeader(
    section: LibrarySection,
    onSelect: (LibrarySection) -> Unit,
    onOpenSettings: () -> Unit,
    marked: Set<LibrarySection>,
    transfers: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Library", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            actions()
            transfers()
            IconButton(onClick = onOpenSettings, modifier = Modifier.testTag(LIBRARY_SETTINGS_TAG)) {
                Icon(Icons.Default.Settings, "Settings")
            }
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            LibrarySection.entries.forEachIndexed { index, entry ->
                val dot = entry != section && entry in marked
                SegmentedButton(
                    selected = entry == section,
                    onClick = { onSelect(entry) },
                    shape = SegmentedButtonDefaults.itemShape(index, LibrarySection.entries.size),
                    modifier = Modifier.testTag(librarySectionTag(entry)).semantics {
                        if (dot) stateDescription = "New"
                    },
                    icon = {},
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(entry.label, maxLines = 1)
                        if (dot) NewDot()
                    }
                }
            }
        }
    }
}

@Composable
fun NewDot(modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
}

const val LIBRARY_SETTINGS_TAG = "library-settings"

fun librarySectionTag(section: LibrarySection) = "library-section-${section.name.lowercase()}"
