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
internal fun rememberSearchTermCounts(
    source: SourceKey,
    terms: List<SearchTerm>,
    supplied: Map<SearchTerm, Int> = emptyMap(),
): Map<SearchTerm, Int?> {
    val typedTerms = remember(terms) { terms.filterNot(SearchTerm::isGeneralPostTag) }
    var counts by remember(source, typedTerms, supplied) { mutableStateOf<Map<SearchTerm, Int?>>(supplied) }
    val fetch by rememberUpdatedState(LocalSearchTermCounts.current)
    LaunchedEffect(source, typedTerms, supplied) {
        val missing = typedTerms.filter { supplied[it] == null }
        if (missing.isNotEmpty()) counts = supplied + fetch(source, missing)
    }
    return counts
}
