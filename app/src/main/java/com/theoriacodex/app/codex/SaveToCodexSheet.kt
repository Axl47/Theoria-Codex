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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import android.content.res.Configuration
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.ui.components.SheetHeader
import com.theoriacodex.data.repository.CodexLibrarySort
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
    relevanceScores: Map<String, Double> = emptyMap(),
    librarySort: CodexLibrarySort = CodexLibrarySort.MANUAL,
    libraryUsage: Map<String, CodexLibraryUsage> = emptyMap(),
    containingCodexIds: Set<String> = emptySet(),
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
        relevanceScores = relevanceScores,
        librarySort = librarySort,
        libraryUsage = libraryUsage,
        containingCodexIds = containingCodexIds,
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
    relevanceScores: Map<String, Double>,
    librarySort: CodexLibrarySort,
    libraryUsage: Map<String, CodexLibraryUsage>,
    containingCodexIds: Set<String>,
    onSelectCodex: (String) -> Unit,
    onCycleProfile: () -> Unit,
    onCreateCodex: () -> Unit,
    onDismiss: () -> Unit,
) {
    var searchText by rememberSaveable { mutableStateOf("") }
    var insideSaved by rememberSaveable(selectedProfile?.profileId) { mutableStateOf(false) }
    var sortByRelevance by rememberSaveable { mutableStateOf(true) }
    val likesId = selectedProfile?.profileId?.let(::likesCodexIdForProfile)
    val rootCodices = remember(selectedCodices, likesId, insideSaved, librarySort, codexItemCounts, libraryUsage) {
        savePickerCodices(
            sortCodicesForLibrary(selectedCodices, librarySort, codexItemCounts, likesId, libraryUsage),
            likesId,
            insideSaved,
        )
    }
    BackHandler(enabled = insideSaved) { insideSaved = false; searchText = "" }
    val matchingCodices = remember(rootCodices, searchText, sortByRelevance, relevanceScores, likesId) {
        matchingSaveCodices(rootCodices, searchText, sortByRelevance, relevanceScores, likesId)
    }
    val displayedContainingCodexIds = if (likesId in containingCodexIds) containingCodexIds + SAVED_CODEX_ID
        else containingCodexIds
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val controls: @Composable () -> Unit = {
        SheetHeader(if (insideSaved) "Saved" else title, "Close save menu", onDismiss) {
            SaveCodexSortToggle(sortByRelevance) { sortByRelevance = it }
        }
        if (insideSaved) {
            SheetActionRow(Icons.AutoMirrored.Filled.ArrowBack, "All Codices") {
                insideSaved = false
                searchText = ""
            }
        }
        CodexPickerSearch(searchText) { searchText = it }
    }
    val actions: @Composable () -> Unit = {
        SheetActionRow(Icons.Default.Person, selectedProfile?.name ?: "Profile", onCycleProfile)
        SheetActionRow(Icons.Default.Add, "Create Codex", onCreateCodex)
    }
    val list: @Composable (Modifier) -> Unit = { modifier ->
        CodexPickerList(
            codices = matchingCodices,
            columns = if (landscape) 2 else 1,
            modifier = modifier,
            emptyMessage = savePickerEmptyMessage(selectedCodices, hasExcludedCodices),
            itemCounts = codexItemCounts + (SAVED_CODEX_ID to (codexItemCounts[likesId] ?: 0)),
            containingCodexIds = displayedContainingCodexIds,
            coverCandidates = codexCoverCandidates + (SAVED_CODEX_ID to codexCoverCandidates[likesId].orEmpty()),
            onSelectCodex = { id ->
                if (id == SAVED_CODEX_ID) { insideSaved = true; searchText = "" }
                else onSelectCodex(id)
            },
        )
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // Landscape is short, so the controls take a side column and the list keeps the full height.
        sheetMaxWidth = if (landscape) Dp.Unspecified else BottomSheetDefaults.SheetMaxWidth,
        dragHandle = null,
    ) {
        val spacing = Arrangement.spacedBy(12.dp)
        val padding = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        if (landscape) {
            Row(padding.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(0.36f).fillMaxHeight(), verticalArrangement = spacing) {
                    controls()
                    Spacer(Modifier.weight(1f))
                    actions()
                }
                list(Modifier.weight(0.64f).fillMaxHeight())
            }
        } else {
            Column(padding.fillMaxWidth(), verticalArrangement = spacing) {
                controls()
                list(Modifier.weight(1f, fill = false).heightIn(max = 420.dp))
                actions()
            }
        }
    }
}

private fun matchingSaveCodices(
    codices: List<Codex>,
    searchText: String,
    sortByRelevance: Boolean,
    relevanceScores: Map<String, Double>,
    likesId: String?,
): List<Codex> {
    val query = searchText.trim()
    val matches = codices.filter { codex ->
        codex.name.contains(query, ignoreCase = true) ||
            (codex.codexId == SAVED_CODEX_ID && "Likes".contains(query, ignoreCase = true))
    }
    return if (sortByRelevance) matches.sortedByDescending { codex ->
        relevanceScores[if (codex.codexId == SAVED_CODEX_ID) likesId else codex.codexId] ?: 0.0
    } else matches
}

@Composable
private fun SaveCodexSortToggle(sortByRelevance: Boolean, onChange: (Boolean) -> Unit) {
    IconToggleButton(checked = sortByRelevance, onCheckedChange = onChange) {
        Icon(
            if (sortByRelevance) Icons.Default.AutoAwesome else Icons.AutoMirrored.Filled.Sort,
            contentDescription = if (sortByRelevance) "Sort: tag relevance. Switch to library order"
                else "Sort: library order. Switch to tag relevance",
        )
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
    columns: Int,
    emptyMessage: String,
    itemCounts: Map<String, Int>,
    coverCandidates: Map<String, List<CodexCoverCandidate>>,
    containingCodexIds: Set<String>,
    onSelectCodex: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyGridState()
    LaunchedEffect(codices) { listState.scrollToItem(0) }
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
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = listState,
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(codices, key = Codex::codexId) { codex ->
            CodexPickerRow(
                codex = codex,
                itemCount = itemCounts[codex.codexId] ?: 0,
                coverCandidates = coverCandidates[codex.codexId].orEmpty(),
                onClick = { onSelectCodex(codex.codexId) },
                enabled = !isVirtualCodex(codex.codexId),
                alreadySaved = codex.codexId in containingCodexIds,
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

/** Marks a post that is already in a Codex, readable on both light and dark surfaces. */
@Composable
@ReadOnlyComposable
internal fun codexSavedColor(): Color =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) Color(0xFF81C784) else Color(0xFF256A2B)

@Composable
private fun CodexPickerRow(
    codex: Codex,
    itemCount: Int,
    coverCandidates: List<CodexCoverCandidate>,
    onClick: () -> Unit,
    enabled: Boolean = true,
    alreadySaved: Boolean = false,
    description: String = "$itemCount items",
) {
    val savedGreen = codexSavedColor()
    Surface(
        color = if (alreadySaved) savedGreen.copy(alpha = 0.16f).compositeOver(MaterialTheme.colorScheme.surface)
            else MaterialTheme.colorScheme.surface,
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
            CodexPickerCover(codex.name, coverCandidates)

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
            if (alreadySaved) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Already in Codex",
                    tint = savedGreen,
                )
            }
        }
    }
}

@Composable
private fun CodexPickerCover(name: String, candidates: List<CodexCoverCandidate>) {
    Box(
        modifier = Modifier
            .size(58.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        CodexCoverImage(
            candidates = candidates,
            contentDescription = name,
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
