package com.theoriacodex.app.series

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.ui.components.ExpandedBottomSheet
import com.theoriacodex.app.ui.components.LoadingState
import com.theoriacodex.app.ui.components.SheetHeader
import com.theoriacodex.app.ui.components.SingleChoiceMenu
import com.theoriacodex.domain.series.Inclusion
import com.theoriacodex.domain.series.SeriesFilter
import com.theoriacodex.domain.series.changedFrom
import com.theoriacodex.domain.series.withChoices

/**
 * Searches one source: a text field, then the source's own [defaults] (null while they load), which start at what
 * [applied] chose when it is on screen. Choices stay in the sheet until Search hands them to [onSearch].
 */
@Composable
internal fun SeriesFilterSheet(
    sourceName: String,
    defaults: List<SeriesFilter>?,
    applied: BrowseKind.Filtered?,
    onDismiss: () -> Unit,
    onSearch: (String, List<SeriesFilter>) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(applied?.text.orEmpty()) }
    var draft by remember(defaults) { mutableStateOf(defaults?.withChoices(applied?.filters.orEmpty())) }
    var expanded by remember { mutableStateOf(emptySet<String>()) }
    val search = { onSearch(text, draft.orEmpty()) }
    ExpandedBottomSheet(onDismiss) {
        SheetHeader(FILTER, "Close filter", onDismiss) {
            TextButton(
                onClick = {
                    text = ""
                    draft = defaults
                },
            ) { Text("Reset") }
        }
        LazyColumn(Modifier.weight(1f, fill = false).padding(horizontal = 16.dp)) {
            item(key = "text") {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    placeholder = { Text("Search $sourceName") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { search() }),
                )
            }
            val filters = draft
            if (filters == null) item(key = "loading") { LoadingState() }
            else filterItems(filters, defaults.orEmpty(), path = "", expanded, { expanded = it }) { draft = it }
        }
        Button(onClick = search, modifier = Modifier.fillMaxWidth().padding(16.dp)) { Text("Search") }
    }
}

/**
 * One lazy item per filter, with a group's members as items of their own while it is open, so a group of hundreds
 * of tags composes only what shows. [path] keys each item by its place in the tree; [onChange] takes [filters] with
 * one of them changed.
 */
private fun LazyListScope.filterItems(
    filters: List<SeriesFilter>,
    defaults: List<SeriesFilter>,
    path: String,
    expanded: Set<String>,
    onExpand: (Set<String>) -> Unit,
    onChange: (List<SeriesFilter>) -> Unit,
) {
    filters.forEachIndexed { index, filter ->
        val key = "$path$index"
        val set: (SeriesFilter) -> Unit = { changed -> onChange(filters.toMutableList().also { it[index] = changed }) }
        if (filter is SeriesFilter.Group) {
            val memberDefaults = (defaults.getOrNull(index) as? SeriesFilter.Group)?.filters.orEmpty()
            val open = key in expanded
            item(key = key) {
                GroupRow(filter.name, filter.filters.changedFrom(memberDefaults), open) { onExpand(if (open) expanded - key else expanded + key) }
            }
            if (open) filterItems(filter.filters, memberDefaults, "$key.", expanded, onExpand) { set(filter.copy(filters = it)) }
        } else {
            item(key = key) { FilterRow(filter, set) }
        }
    }
}

@Composable
private fun FilterRow(filter: SeriesFilter, onChange: (SeriesFilter) -> Unit) {
    when (filter) {
        is SeriesFilter.Header -> FilterLabel(filter.name)
        SeriesFilter.Separator -> HorizontalDivider(Modifier.padding(vertical = 8.dp))
        is SeriesFilter.Text -> OutlinedTextField(
            value = filter.value,
            onValueChange = { onChange(filter.copy(value = it)) },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            label = { Text(filter.name) },
            singleLine = true,
        )
        is SeriesFilter.Check -> OptionRow(
            filter.name,
            Modifier.toggleable(filter.checked, role = Role.Checkbox) { onChange(filter.copy(checked = it)) },
        ) { Checkbox(filter.checked, onCheckedChange = null) }
        is SeriesFilter.TriState -> TriStateRow(filter, onChange)
        is SeriesFilter.Select -> SelectRow(filter, onChange)
        is SeriesFilter.Sort -> SortRows(filter, onChange)
        // Groups are drawn by filterItems, which gives each member a lazy item of its own.
        is SeriesFilter.Group -> Unit
    }
}

/** A box that cycles ignored → included → excluded; excluded shows the error color's dash. */
@Composable
private fun TriStateRow(filter: SeriesFilter.TriState, onChange: (SeriesFilter) -> Unit) {
    val state = when (filter.state) {
        Inclusion.IGNORE -> ToggleableState.Off
        Inclusion.INCLUDE -> ToggleableState.On
        Inclusion.EXCLUDE -> ToggleableState.Indeterminate
    }
    val next = Inclusion.entries[(filter.state.ordinal + 1) % Inclusion.entries.size]
    OptionRow(
        filter.name,
        Modifier
            .triStateToggleable(state, role = Role.Checkbox) { onChange(filter.copy(state = next)) }
            // TalkBack would call the excluded dash "partially checked".
            .semantics {
                stateDescription = when (filter.state) {
                    Inclusion.IGNORE -> "Ignored"
                    Inclusion.INCLUDE -> "Included"
                    Inclusion.EXCLUDE -> "Excluded"
                }
            },
    ) {
        val excluded = MaterialTheme.colorScheme.error
        TriStateCheckbox(state, onClick = null, colors = if (filter.state == Inclusion.EXCLUDE) CheckboxDefaults.colors(checkedColor = excluded) else CheckboxDefaults.colors())
    }
}

/** The filter's name and its current option; the options open in a menu. */
@Composable
private fun SelectRow(filter: SeriesFilter.Select, onChange: (SeriesFilter) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.DropdownList) { open = true }.padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(filter.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            filter.options.getOrNull(filter.selected)?.let { option ->
                Text(option, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        SingleChoiceMenu(
            expanded = open,
            onDismiss = { open = false },
            options = filter.options.indices.toList(),
            selected = filter.selected,
            label = filter.options::get,
            onSelect = { onChange(filter.copy(selected = it)) },
        )
    }
}

/** A row per order; tapping the chosen one flips its direction, shown by an arrow. */
@Composable
private fun SortRows(filter: SeriesFilter.Sort, onChange: (SeriesFilter) -> Unit) {
    Column {
        FilterLabel(filter.name)
        filter.options.forEachIndexed { index, option ->
            // The chosen order's direction, null for the others; a newly chosen order starts descending.
            val ascending = filter.selection?.takeIf { it.index == index }?.ascending
            SortOption(option, ascending) {
                onChange(filter.copy(selection = SeriesFilter.Sort.Selection(index, ascending = ascending == false)))
            }
        }
    }
}

@Composable
private fun SortOption(label: String, ascending: Boolean?, onClick: () -> Unit) {
    val direction = when (ascending) {
        null -> null
        true -> "Ascending" to Icons.Default.ArrowUpward
        false -> "Descending" to Icons.Default.ArrowDownward
    }
    OptionRow(
        label,
        Modifier
            .selectable(direction != null, role = Role.RadioButton, onClick = onClick)
            .semantics { direction?.let { stateDescription = it.first } },
    ) {
        direction?.let { Icon(it.second, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
    }
}

/** A group's title, how many of its members are changed, and whether it is open. */
@Composable
private fun GroupRow(name: String, changed: Int, open: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .semantics {
                if (open) collapse { onToggle(); true } else expand { onToggle(); true }
            }
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        if (changed > 0) Text("$changed", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
    }
}

@Composable
private fun FilterLabel(name: String) {
    Text(
        name,
        Modifier.padding(top = 16.dp, bottom = 4.dp).semantics { heading() },
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** A control in a touch-sized slot at the start, then its label; [modifier] makes the whole row the control. */
@Composable
private fun OptionRow(label: String, modifier: Modifier, control: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { control() }
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

internal const val FILTER = "Filter"
