package com.theoriacodex.app.codex

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.theoriacodex.data.repository.RecommendationProfile
import com.theoriacodex.domain.model.Codex

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaveToCodexSheet(
    profiles: List<RecommendationProfile>,
    initialProfileId: String,
    codicesByProfile: Map<String, List<Codex>>,
    codexItemCounts: Map<String, Int>,
    codexCoverModels: Map<String, Any?>,
    onCreateCodex: (String, String) -> Unit,
    onSelectCodex: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val profileKey = remember(profiles) {
        profiles.joinToString(separator = "|") { it.profileId }
    }
    var selectedProfileId by rememberSaveable(profileKey, initialProfileId) {
        mutableStateOf(
            initialProfileId.takeIf { profileId -> profiles.any { profile -> profile.profileId == profileId } }
                ?: profiles.firstOrNull()?.profileId.orEmpty()
        )
    }
    var showCreateDialog by remember { mutableStateOf(false) }

    LaunchedEffect(profiles, initialProfileId) {
        if (profiles.isEmpty()) {
            selectedProfileId = ""
            return@LaunchedEffect
        }
        if (profiles.none { profile -> profile.profileId == selectedProfileId }) {
            selectedProfileId = initialProfileId
                .takeIf { profileId -> profiles.any { profile -> profile.profileId == profileId } }
                ?: profiles.first().profileId
        }
    }

    val selectedProfile = profiles.firstOrNull { profile -> profile.profileId == selectedProfileId }
    val selectedCodices = codicesByProfile[selectedProfileId].orEmpty()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close save menu",
                    )
                }
                Text(
                    text = "Save to Codex",
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.size(48.dp))
            }

            if (selectedProfile != null) {
                Text(
                    text = "Profile: ${selectedProfile.name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }

            if (selectedCodices.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    tonalElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "No codices in this profile yet.",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 20.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(selectedCodices, key = { codex -> codex.codexId }) { codex ->
                        CodexPickerRow(
                            codex = codex,
                            itemCount = codexItemCounts[codex.codexId] ?: 0,
                            coverModel = codexCoverModels[codex.codexId],
                            onClick = { onSelectCodex(codex.codexId) },
                        )
                    }
                }
            }

            SheetActionRow(
                icon = Icons.Default.Person,
                label = selectedProfile?.name ?: "Profile",
                onClick = {
                    selectedProfileId = nextProfileId(
                        profiles = profiles,
                        currentProfileId = selectedProfileId,
                    )
                },
            )
            SheetActionRow(
                icon = Icons.Default.Add,
                label = "Create Codex",
                onClick = { showCreateDialog = true },
            )
        }
    }

    if (showCreateDialog) {
        CreateCodexDialog(
            profileName = selectedProfile?.name,
            onDismiss = { showCreateDialog = false },
            onCreate = { name ->
                val targetProfileId = selectedProfileId.takeIf { it.isNotBlank() }
                    ?: profiles.firstOrNull()?.profileId
                    ?: return@CreateCodexDialog
                onCreateCodex(targetProfileId, name)
                showCreateDialog = false
            },
        )
    }
}

@Composable
private fun CodexPickerRow(
    codex: Codex,
    itemCount: Int,
    coverModel: Any?,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (coverModel != null) {
                    AsyncImage(
                        model = coverModel,
                        contentDescription = codex.name,
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Collections,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = codex.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "$itemCount items",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SheetActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            modifier = Modifier.size(56.dp),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CreateCodexDialog(
    profileName: String?,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var value by remember { mutableStateOf("") }

    fun createIfValid() {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return
        onCreate(trimmed)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Codex") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!profileName.isNullOrBlank()) {
                    Text(
                        text = "Profile: $profileName",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = value,
                    onValueChange = { input -> value = input.replace("\n", " ") },
                    label = { Text("Codex name") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { createIfValid() }),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        confirmButton = {
            TextButton(onClick = { createIfValid() }) {
                Text("Create")
            }
        },
    )
}

private fun nextProfileId(
    profiles: List<RecommendationProfile>,
    currentProfileId: String,
): String {
    if (profiles.isEmpty()) return currentProfileId
    val index = profiles.indexOfFirst { profile -> profile.profileId == currentProfileId }
    val nextIndex = if (index < 0) 0 else (index + 1) % profiles.size
    return profiles[nextIndex].profileId
}
