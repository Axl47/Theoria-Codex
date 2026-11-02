package com.theoriacodex.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Bottom sheet that opens fully expanded, shared by long-press menus and their follow-up sheets. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExpandedBottomSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        content = content,
    )
}

/** Scrollable long-press menu: compose an [ActionMenuHeader], then rows and dividers. */
@Composable
internal fun ActionMenuSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ExpandedBottomSheet(onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            content = content,
        )
    }
}

/** Names what the menu acts on; [image] receives the thumbnail's size, shape, and backdrop. */
@Composable
internal fun ActionMenuHeader(
    title: String,
    image: @Composable (Modifier) -> Unit,
    details: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        image(
            Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Column {
            Text(
                title, style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            details()
        }
    }
}

/** Secondary text for an [ActionMenuHeader]. */
@Composable
internal fun ActionMenuDetailText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Separates a menu's action groups. */
@Composable
internal fun ActionMenuDivider() {
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
}

/** One labeled menu action. Pass the error color as [tint] for destructive actions. */
@Composable
internal fun ActionMenuRow(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    val color = if (enabled) tint else tint.copy(alpha = 0.38f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = color)
        Text(
            label, style = MaterialTheme.typography.bodyLarge, color = color,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A menu row with custom [leading] content and optional secondary text at its end. */
@Composable
internal fun ActionMenuContentRow(
    label: String,
    leading: @Composable () -> Unit,
    trailingText: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        trailingText?.let { ActionMenuDetailText(it) }
    }
}

/** The tinted row that leads from a menu into its dedicated follow-up sheet. */
@Composable
internal fun ActionMenuFollowUpRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
        contentColor = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null)
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
        }
    }
}
