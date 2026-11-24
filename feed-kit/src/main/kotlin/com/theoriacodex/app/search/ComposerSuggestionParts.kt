package com.theoriacodex.app.search

import com.theoriacodex.app.post.PostTagSelection
import com.theoriacodex.app.tags.TagChipState
import com.theoriacodex.app.tags.chipState
import com.theoriacodex.data.repository.FavoriteTagEntry
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.tags.sourceTagKey

val FavoriteTagEntry.key: String get() = tags.joinToString("\n")

val FavoriteTagEntry.terms: List<SearchTerm> get() = tags.map(::SearchTerm)

val FavoriteTagEntry.alternativesText: String get() = tags.joinToString(" or ")

val FavoriteTagEntry.label: String get() = name ?: alternativesText

fun FavoriteTagEntry.matches(terms: List<SearchTerm>): Boolean =
    terms.isNotEmpty() && terms.all(SearchTerm::isGeneralTag) &&
        terms.mapTo(mutableSetOf()) { sourceTagKey(source, it.value) } == tags.mapTo(mutableSetOf()) { sourceTagKey(source, it) }

fun FavoriteTagEntry.groupIndexIn(draft: Query): Int =
    draft.effectiveIncludeTermGroups.indexOfFirst { group -> matches(group.terms) }

fun FavoriteTagEntry.state(draft: Query): TagChipState = when (val tag = terms.singleOrNull()) {
    null -> if (groupIndexIn(draft) >= 0) TagChipState.REQUIRED else TagChipState.NONE
    else -> PostTagSelection(source, draft.includeTerms, draft.excludeTerms).chipState(tag)
}
