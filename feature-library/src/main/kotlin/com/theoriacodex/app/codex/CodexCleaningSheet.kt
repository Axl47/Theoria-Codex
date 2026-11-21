package com.theoriacodex.app.codex

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.ui.navigation.LocalTriageOpener
import com.theoriacodex.app.ui.navigation.TriageMode
import com.theoriacodex.app.ui.navigation.TriageOpener
import com.theoriacodex.app.ui.navigation.TriageRequest
import com.theoriacodex.domain.model.Codex
import com.theoriacodex.domain.model.PostId

/**
 * The opened collection is the target; comparison collections are read-only inputs. [matches] holds, per
 * comparison, the target's posts it also holds; empty until known, when rows show no counts. [onReview], when
 * given, walks through those posts in the triage deck instead of removing them all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CodexCleaningSheet(
    target: Codex,
    codices: List<Codex>,
    isLikesCodex: Boolean,
    matches: Map<String, Set<PostId>>,
    onDismiss: () -> Unit,
    onClean: (Set<String>) -> Unit,
    onReview: ((Set<String>) -> Unit)? = null,
) {
    var selected by remember(target.codexId) { mutableStateOf(emptySet<String>()) }
    val comparisons = codexCleaningComparisons(target, codices)
    val selectedExisting = selected.intersect(comparisons.mapTo(mutableSetOf(), Codex::codexId))
    val removing = codexCleaningRemovalCount(selectedExisting, matches)
    ModalBottomSheet(onDismissRequest = onDismiss, dragHandle = null) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Clean ${target.name}", style = MaterialTheme.typography.titleLarge)
            if (isLikesCodex) Text("Matching posts will also be unliked.")
            Text("Remove posts also in", style = MaterialTheme.typography.titleMedium)
            if (comparisons.isEmpty()) Text("Create another Codex to compare with.")
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                items(comparisons, key = Codex::codexId) { codex ->
                    val checked = codex.codexId in selectedExisting
                    Row(
                        modifier = Modifier.fillMaxWidth().toggleable(
                            value = checked,
                            role = Role.Checkbox,
                            onValueChange = { enabled ->
                                selected = if (enabled) selected + codex.codexId else selected - codex.codexId
                            },
                        ).padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Text(codex.name, modifier = Modifier.weight(1f).padding(end = 8.dp))
                        matches[codex.codexId]?.let { posts ->
                            Text(
                                posts.size.toString(),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            CleaningActions(selectedExisting, removing, onDismiss, onClean, onReview)
        }
    }
}

/**
 * Cleaning's buttons for the [selected] comparisons, which would remove [removing] posts (null until known): Review,
 * or Cancel where there is no review, and Remove. The sheet dismisses itself, so Review takes Cancel's place.
 */
@Composable
private fun CleaningActions(
    selected: Set<String>,
    removing: Int?,
    onDismiss: () -> Unit,
    onClean: (Set<String>) -> Unit,
    onReview: ((Set<String>) -> Unit)?,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
        if (onReview == null) {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        } else {
            TextButton(enabled = (removing ?: 0) > 0, onClick = { onReview(selected) }) { Text("Review") }
        }
        Button(enabled = selected.isNotEmpty() && removing != 0, onClick = { onClean(selected) }) {
            Text(removing?.let(::codexPostCount)?.let { "Remove $it" } ?: "Clean Codex")
        }
    }
}

/**
 * Review for cleaning Codex [codexId]: closes the sheet with [onOpen] and opens the triage deck on the posts the
 * chosen comparisons also hold, where left removes. Null for no Codex (Likes, whose removal would unlike) or when the
 * app provides no deck.
 */
@Composable
internal fun rememberCleaningReview(codexId: String?, onOpen: () -> Unit): ((Set<String>) -> Unit)? {
    val triage = LocalTriageOpener.current
    val open by rememberUpdatedState(onOpen)
    return remember(codexId, triage) {
        if (codexId == null || triage === TriageOpener.None) return@remember null
        { comparisonIds: Set<String> ->
            open()
            triage.openTriage(TriageRequest.codex(codexId, TriageMode.RemoveFromCodex(comparisonIds)))
        }
    }
}

/** The Codices [target] can be cleaned against: every other real Codex. */
internal fun codexCleaningComparisons(target: Codex, codices: List<Codex>): List<Codex> =
    codices.filter { it.codexId != target.codexId && !isVirtualCodex(it.codexId) }

/**
 * How many posts cleaning against [selected] removes: a post held by several of them counts once. Null when
 * nothing is selected or a selected Codex's matches are not known yet.
 */
internal fun codexCleaningRemovalCount(selected: Set<String>, matches: Map<String, Set<PostId>>): Int? {
    if (selected.isEmpty() || !matches.keys.containsAll(selected)) return null
    return selected.flatMapTo(hashSetOf()) { matches.getValue(it) }.size
}
