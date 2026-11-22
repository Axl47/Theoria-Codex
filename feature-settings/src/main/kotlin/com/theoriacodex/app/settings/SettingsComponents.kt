package com.theoriacodex.app.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.theoriacodex.data.repository.SettingDestinations

// The header, group and row every Settings page is built from live in ui-kit (SettingsRows.kt), shared with other
// features' settings-style pages.

/** The path of every page directly under the Settings home. */
val SETTINGS_PATH = listOf(SettingDestinations.Home.title)

/**
 * A collapsed item that reveals [content] when tapped, like Advanced. It returns to
 * [initiallyExpanded] when that changes, so saving keys folds their section away.
 */
@Composable
fun ExpandableSection(label: String, initiallyExpanded: Boolean, content: @Composable () -> Unit) {
    var expanded by rememberSaveable(initiallyExpanded) { mutableStateOf(initiallyExpanded) }
    TextButton(
        onClick = { expanded = !expanded },
        modifier = Modifier.semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" },
    ) {
        Text(label)
        Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
    }
    if (expanded) content()
}
