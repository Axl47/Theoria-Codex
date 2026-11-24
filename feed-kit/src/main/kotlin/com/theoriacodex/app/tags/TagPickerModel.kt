package com.theoriacodex.app.tags

import com.theoriacodex.app.post.PostTagSelection
import com.theoriacodex.app.search.state.SearchDraftReducer
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SearchTermGroup
import com.theoriacodex.domain.model.SourceKey

enum class TagChipState(val description: String, val mark: String?) {
    NONE("Not in search", null),
    REQUIRED("Required", "+"),
    EXCLUDED("Excluded", "−"),
}

fun PostTagSelection.chipState(term: SearchTerm): TagChipState = when {
    included(term) != null -> TagChipState.REQUIRED
    excluded(term) != null -> TagChipState.EXCLUDED
    else -> TagChipState.NONE
}

sealed interface TagTapEffect {
    data object AddRequired : TagTapEffect
    data class AddAlternative(val groupIndex: Int) : TagTapEffect
    data class SwitchToExcluded(val stored: SearchTerm) : TagTapEffect
    data class Clear(val stored: SearchTerm) : TagTapEffect
}

fun tagTapEffect(term: SearchTerm, selection: PostTagSelection, targetGroup: Int?): TagTapEffect {
    selection.included(term)?.let { return TagTapEffect.SwitchToExcluded(it) }
    selection.excluded(term)?.let { return TagTapEffect.Clear(it) }
    return targetGroup?.let(TagTapEffect::AddAlternative) ?: TagTapEffect.AddRequired
}

fun tagTapLabel(name: String, state: TagChipState): String = when (state) {
    TagChipState.NONE -> "Include $name in Search"
    TagChipState.REQUIRED -> "Exclude $name from Search"
    TagChipState.EXCLUDED -> "Remove $name from Search"
}

fun Query.withTagTap(source: SourceKey, term: SearchTerm, targetGroup: Int?): Query {
    val selection = PostTagSelection(source, includeTerms, excludeTerms)
    return when (val effect = tagTapEffect(term, selection, targetGroup)) {
        TagTapEffect.AddRequired -> withIncludeTermGroups(effectiveIncludeTermGroups + SearchTermGroup.single(term))
        is TagTapEffect.AddAlternative ->
            SearchDraftReducer.postTermForGroup(this, source, effect.groupIndex, term)?.let { joined ->
                withIncludeTermGroups(
                    effectiveIncludeTermGroups.mapIndexed { index, group ->
                        if (index == effect.groupIndex) SearchTermGroup(group.terms + joined) else group
                    },
                )
            } ?: this
        is TagTapEffect.SwitchToExcluded -> withoutIncludeTerm(effect.stored).copy(excludeTerms = excludeTerms + effect.stored)
        is TagTapEffect.Clear -> copy(excludeTerms = excludeTerms - effect.stored)
    }
}

private fun Query.withoutIncludeTerm(term: SearchTerm): Query = withIncludeTermGroups(
    effectiveIncludeTermGroups.mapNotNull { group ->
        (group.terms - term).takeIf(List<SearchTerm>::isNotEmpty)?.let(::SearchTermGroup)
    },
)

fun compactTagCount(count: Int): String {
    val (unit, suffix) = when {
        count >= 1_000_000_000 -> 1_000_000_000 to "b"
        count >= 1_000_000 -> 1_000_000 to "m"
        count >= 1_000 -> 1_000 to "k"
        else -> return count.toString()
    }
    val whole = count / unit
    val tenth = count / (unit / 10) % 10
    return if (whole < 10 && tenth > 0) "$whole.$tenth$suffix" else "$whole$suffix"
}
