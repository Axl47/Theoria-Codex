package com.theoriacodex.app.ui.components

import androidx.compose.material3.FilterChip
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.theoriacodex.app.source.SourceIcon
import com.theoriacodex.app.source.displayName
import com.theoriacodex.domain.model.SourceKey

/** A source choice shown by its icon, named for accessibility. */
@Composable
internal fun SourceFilterChip(source: SourceKey, selected: Boolean, onClick: () -> Unit) {
    val name = source.displayName()
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { SourceIcon(source) },
        modifier = Modifier.semantics { contentDescription = name },
    )
}
