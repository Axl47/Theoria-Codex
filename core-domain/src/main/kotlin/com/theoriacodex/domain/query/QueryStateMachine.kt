package com.theoriacodex.domain.query

import com.theoriacodex.domain.model.Query

data class SearchQueryState(
    val draftQuery: Query,
    val appliedQuery: Query,
) {
    val hasPendingChanges: Boolean
        get() = draftQuery != appliedQuery

    fun applyDraft(): SearchQueryState {
        return copy(appliedQuery = draftQuery)
    }

    fun resetDraft(): SearchQueryState {
        return copy(draftQuery = appliedQuery)
    }

    fun updateDraft(transform: (Query) -> Query): SearchQueryState {
        return copy(draftQuery = transform(draftQuery))
    }

    companion object {
        fun fromApplied(query: Query): SearchQueryState {
            return SearchQueryState(
                draftQuery = query,
                appliedQuery = query,
            )
        }
    }
}
