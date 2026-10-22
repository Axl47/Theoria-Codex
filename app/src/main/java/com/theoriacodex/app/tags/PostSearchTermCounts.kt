package com.theoriacodex.app.tags

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SourceKey

@Composable
internal fun rememberSearchTermCounts(source: SourceKey, terms: List<SearchTerm>): Map<SearchTerm, Int?> {
    val typedTerms = remember(terms) { terms.filterNot(SearchTerm::isGeneralPostTag) }
    var counts by remember(source, typedTerms) { mutableStateOf<Map<SearchTerm, Int?>>(emptyMap()) }
    val fetch by rememberUpdatedState(LocalSearchTermCounts.current)
    LaunchedEffect(source, typedTerms) {
        if (typedTerms.isNotEmpty()) counts = fetch(source, typedTerms)
    }
    return counts
}
