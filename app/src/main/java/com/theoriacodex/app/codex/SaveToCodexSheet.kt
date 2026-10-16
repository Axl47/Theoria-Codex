package com.theoriacodex.app.codex

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.ui.components.SheetHeader
import com.theoriacodex.data.repository.RecommendationProfile
import com.theoriacodex.domain.model.Codex

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SaveToCodexSheet(
    profiles: List<RecommendationProfile>,
    initialProfileId: String,
    codicesByProfile: Map<String, List<Codex>>,
    codexItemCounts: Map<String, Int>,
    codexCoverCandidates: Map<String, List<CodexCoverCandidate>>,
    excludedCodexIds: Set<String> = emptySet(),
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
    val profileCodices = codicesByProfile[selectedProfileId].orEmpty()
    val selectedCodices = availableSaveTargetCodices(profileCodices, excludedCodexIds)

    SaveToCodexSheetContent(
        title = if (excludedCodexIds.isEmpty()) "Save to Codex" else "Add to another Codex",
        selectedProfile = selectedProfile,
        selectedCodices = selectedCodices,
        hasExcludedCodices = profileCodices.size != selectedCodices.size,
        codexItemCounts = codexItemCounts,
        codexCoverCandidates = codexCoverCandidates,
        onSelectCodex = onSelectCodex,
        onCycleProfile = {
            selectedProfileId = nextProfileId(profiles, selectedProfileId)
        },
        onCreateCodex = { showCreateDialog = true },
        onDismiss = onDismiss,
    )

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SaveToCodexSheetContent(
    title: String,
    selectedProfile: RecommendationProfile?,
    selectedCodices: List<Codex>,
    hasExcludedCodices: Boolean,
    codexItemCounts: Map<String, Int>,
    codexCoverCandidates: Map<String, List<CodexCoverCandidate>>,
    onSelectCodex: (String) -> Unit,
    onCycleProfile: () -> Unit,
    onCreateCodex: () -> Unit,
    onDismiss: () -> Unit,
) {
    var searchText by rememberSaveable { mutableStateOf("") }
    var insideSaved by rememberSaveable(selectedProfile?.profileId) { mutableStateOf(false) }
    val likesId = selectedProfile?.profileId?.let(::likesCodexIdForProfile)
    val rootCodices = remember(selectedCodices, likesId, insideSaved) {
        savePickerCodices(selectedCodices, likesId, insideSaved)
    }
    BackHandler(enabled = insideSaved) { insideSaved = false; searchText = "" }
    val matchingCodices = remember(rootCodices, searchText) {
        val query = searchText.trim()
        rootCodices.filter { codex ->
            codex.name.contains(query, ignoreCase = true) ||
                (codex.codexId == SAVED_CODEX_ID && "Likes".contains(query, ignoreCase = true))
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SheetHeader(if (insideSaved) "Saved" else title, "Close save menu", onDismiss)
            if (insideSaved) {
                SheetActionRow(Icons.AutoMirrored.Filled.ArrowBack, "All Codices") {
                    insideSaved = false
                    searchText = ""
                }
            }
            CodexPickerSearch(searchText) { searchText = it }
            CodexPickerList(
                codices = matchingCodices,
                modifier = Modifier.weight(1f, fill = false),
                emptyMessage = savePickerEmptyMessage(selectedCodices, hasExcludedCodices),
                itemCounts = codexItemCounts + (SAVED_CODEX_ID to (codexItemCounts[likesId] ?: 0)),
                coverCandidates = codexCoverCandidates + (SAVED_CODEX_ID to codexCoverCandidates[likesId].orEmpty()),
                onSelectCodex = { id ->
                    if (id == SAVED_CODEX_ID) { insideSaved = true; searchText = "" }
                    else onSelectCodex(id)
                },
            )
            SheetActionRow(Icons.Default.Person, selectedProfile?.name ?: "Profile", onCycleProfile)
            SheetActionRow(Icons.Default.Add, "Create Codex", onCreateCodex)
        }
    }
}

private fun savePickerCodices(codices: List<Codex>, likesId: String?, insideSaved: Boolean): List<Codex> {
    if (insideSaved) return savedCodexChildren(likesId.orEmpty()).filter { child ->
        isVirtualCodex(child.codexId) || codices.any { it.codexId == child.codexId }
    }
    if (likesId == null || codices.none { it.codexId == likesId }) return codices
    return savedCodexLibraryEntries(codices, likesId)
}

private fun savePickerEmptyMessage(codices: List<Codex>, hasExcluded: Boolean): String = when {
    codices.isNotEmpty() -> "No matching Codices."
    hasExcluded -> "No other Codices in this profile yet."
    else -> "No Codices in this profile yet."
}

@Composable
private fun CodexPickerSearch(searchText: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = searchText,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Search Codices") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (searchText.isNotEmpty()) {
                IconButton(onClick = { onChange("") }) {
                    Icon(Icons.Default.Close, contentDescription = "Clear Codex search")
                }
            }
        },
        singleLine = true,
    )
}

@Composable
private fun CodexPickerList(
    codices: List<Codex>,
    emptyMessage: String,
    itemCounts: Map<String, Int>,
    coverCandidates: Map<String, List<CodexCoverCandidate>>,
    onSelectCodex: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (codices.isEmpty()) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = emptyMessage,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    LazyColumn(
        modifier = modifier.heightIn(max = 420.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(codices, key = Codex::codexId) { codex ->
            CodexPickerRow(
                codex = codex,
                itemCount = itemCounts[codex.codexId] ?: 0,
                coverCandidates = coverCandidates[codex.codexId].orEmpty(),
                onClick = { onSelectCodex(codex.codexId) },
                enabled = !isVirtualCodex(codex.codexId),
                description = if (isVirtualCodex(codex.codexId)) "Automatic feed"
                    else "${itemCounts[codex.codexId] ?: 0} items",
            )
        }
    }
}

internal fun availableSaveTargetCodices(
    codices: List<Codex>,
    excludedCodexIds: Set<String>,
): List<Codex> = codices.filterNot { codex -> codex.codexId in excludedCodexIds }

@Composable
private fun CodexPickerRow(
    codex: Codex,
    itemCount: Int,
    coverCandidates: List<CodexCoverCandidate>,
    onClick: () -> Unit,
    enabled: Boolean = true,
    description: String = "$itemCount items",
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
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
                CodexCoverImage(
                    candidates = coverCandidates,
                    contentDescription = codex.name,
                    modifier = Modifier.fillMaxWidth(),
                    fallback = {
                        Icon(
                            imageVector = Icons.Default.Collections,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
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
                    text = description,
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
