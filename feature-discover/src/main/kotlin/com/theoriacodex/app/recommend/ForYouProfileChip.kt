package com.theoriacodex.app.recommend

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theoriacodex.data.repository.RecommendationProfile

/**
 * The active recommendation profile in For You's header, with a menu that switches to another. Drawn only
 * when there is more than one profile to switch between.
 */
@Composable
internal fun ForYouProfileChip(
    profiles: List<RecommendationProfile>,
    activeProfileId: String,
    onSelect: (String) -> Unit,
) {
    if (profiles.size < 2) return
    var expanded by remember { mutableStateOf(false) }
    val active = profiles.firstOrNull { it.profileId == activeProfileId } ?: return
    Box {
        AssistChip(
            onClick = { expanded = true },
            label = { Text(active.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) },
            modifier = Modifier.widthIn(max = 160.dp),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            profiles.forEach { profile ->
                DropdownMenuItem(
                    text = { Text(profile.name) },
                    leadingIcon = { if (profile.profileId == activeProfileId) Icon(Icons.Default.Check, null) },
                    onClick = {
                        expanded = false
                        if (profile.profileId != activeProfileId) onSelect(profile.profileId)
                    },
                )
            }
        }
    }
}
