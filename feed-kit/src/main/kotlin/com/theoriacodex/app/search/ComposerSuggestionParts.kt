package com.theoriacodex.app.search

import com.theoriacodex.app.post.PostTagSelection
import com.theoriacodex.app.tags.TagChipState
import com.theoriacodex.app.tags.chipState
import com.theoriacodex.data.repository.FavoriteTagEntry
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.tags.sourceTagKey

/*
 * Saved tags are general tags kept per source, whose rules decide when two spellings are the same
 * tag. An entry with several tags is an "or" group: it joins the draft as one group of alternatives.
 */

/** Identifies an entry among chips: a tag never holds a line break, so the key cannot collide. */
val FavoriteTagEntry.key: String get() = tags.joinToString("\n")

/** The draft terms a saved entry stands for. */
val FavoriteTagEntry.terms: List<SearchTerm> get() = tags.map(::SearchTerm)

/** A group's alternatives as one phrase, as it reads when the group has no name. */
val FavoriteTagEntry.alternativesText: String get() = tags.joinToString(" or ")

/** The entry's chip text: a group's name, else its alternatives. */
val FavoriteTagEntry.label: String get() = name ?: alternativesText

/** True when [terms] are exactly this entry's tags, in any order. */
fun FavoriteTagEntry.matches(terms: List<SearchTerm>): Boolean =
    terms.isNotEmpty() && terms.all(SearchTerm::isGeneralTag) &&
        terms.mapTo(mutableSetOf()) { sourceTagKey(source, it.value) } == tags.mapTo(mutableSetOf()) { sourceTagKey(source, it) }

fun FavoriteTagEntry.groupIndexIn(draft: Query): Int =
    draft.effectiveIncludeTermGroups.indexOfFirst { group -> matches(group.terms) }

/** How a saved entry currently relates to the draft. A group is only ever required or absent. */
fun FavoriteTagEntry.state(draft: Query): TagChipState = when (val tag = terms.singleOrNull()) {
    null -> if (groupIndexIn(draft) >= 0) TagChipState.REQUIRED else TagChipState.NONE
    else -> PostTagSelection(source, draft.includeTerms, draft.excludeTerms).chipState(tag)
}
