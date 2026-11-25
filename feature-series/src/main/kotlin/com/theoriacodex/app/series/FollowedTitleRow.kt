package com.theoriacodex.app.series

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesId

internal class TitleActions(
    val canSwitch: (Series) -> Boolean,
    val onSwitch: (Series) -> Unit,
    val onUnfollow: (SeriesId) -> Unit,
    val onOpen: ((Series) -> Unit)? = null,
    val summary: (Series) -> String?,
)

@Composable
internal fun FollowedTitleRow(series: Series, nested: Boolean, actions: TitleActions) {
    var menu by remember { mutableStateOf(false) }
    val open = actions.onOpen
    Row(
        Modifier.fillMaxWidth()
            .then(if (open == null) Modifier else Modifier.clickable { open(series) })
            .heightIn(min = 48.dp)
            .padding(start = if (nested) 64.dp else 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(series.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            actions.summary(series)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More options") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (actions.canSwitch(series)) {
                    DropdownMenuItem(text = { Text(SWITCH_SOURCE) }, onClick = {
                        menu = false
                        actions.onSwitch(series)
                    })
                }
                DropdownMenuItem(text = { Text(UNFOLLOW) }, onClick = {
                    menu = false
                    actions.onUnfollow(series.id)
                })
            }
        }
    }
}

internal const val UNFOLLOW = "Unfollow"
