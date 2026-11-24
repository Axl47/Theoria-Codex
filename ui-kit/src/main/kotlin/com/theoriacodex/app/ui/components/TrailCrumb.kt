package com.theoriacodex.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class TrailCrumbArrow { None, Before, After }

@Composable
fun TrailCrumb(
    label: String,
    current: Boolean,
    color: Color,
    dimColor: Color,
    arrow: TrailCrumbArrow,
    maxWidth: Dp,
    verticalPadding: Dp,
    testTag: String,
    onClick: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (arrow == TrailCrumbArrow.Before) CrumbArrow(dimColor)
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
            color = if (current) color else dimColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .widthIn(max = maxWidth)
                .clip(RoundedCornerShape(8.dp))
                .then(if (current) Modifier else Modifier.clickable(role = Role.Button, onClickLabel = "Back to $label", onClick = onClick))
                .padding(horizontal = 4.dp, vertical = verticalPadding)
                .testTag(testTag),
        )
        if (arrow == TrailCrumbArrow.After) CrumbArrow(dimColor)
    }
}

@Composable
private fun CrumbArrow(tint: Color) {
    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
}
