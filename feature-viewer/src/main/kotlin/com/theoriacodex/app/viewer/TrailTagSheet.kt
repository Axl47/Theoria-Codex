package com.theoriacodex.app.viewer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.tags.LocalTagSuggestions
import com.theoriacodex.app.tags.compactTagCount
import com.theoriacodex.app.ui.components.ExpandedBottomSheet
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.tags.sourceAutocompletePrefix
import kotlinx.coroutines.delay

@Composable
internal fun TrailTagSheet(source: SourceKey, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val suggest = LocalTagSuggestions.current
    var input by remember { mutableStateOf("") }
    val asked = source to input
    val answered by produceState(asked to emptyList<TagSuggestion>(), asked) {
        if (input.isBlank()) return@produceState
        delay(SUGGEST_DEBOUNCE_MS)
        value = asked to suggest(source, input)
    }
    val suggestions = answered.second.takeIf { answered.first == asked }.orEmpty()
    val focus = remember { FocusRequester() }
    LaunchedEffect(focus) { focus.requestFocus() }
    ExpandedBottomSheet(onDismiss) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).focusRequester(focus).testTag("trail-tag-field"),
            placeholder = { Text("Tag") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { typedTag(source, input)?.let(onPick) }),
        )
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp).padding(bottom = 12.dp)) {
            items(suggestions, key = { it.text }) { suggestion -> TrailTagSuggestionRow(suggestion) { onPick(suggestion.text) } }
        }
    }
}

@Composable
private fun TrailTagSuggestionRow(suggestion: TagSuggestion, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick).padding(horizontal = 24.dp).testTag("trail-tag-suggestion-${suggestion.text}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(suggestion.text.replace('_', ' '), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        suggestion.count?.let { count ->
            Text(compactTagCount(count), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun typedTag(source: SourceKey, input: String): String? = sourceAutocompletePrefix(source, input.trim()).takeIf(String::isNotBlank)

private const val SUGGEST_DEBOUNCE_MS = 200L
