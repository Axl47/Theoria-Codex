package com.theoriacodex.domain.query

import com.theoriacodex.domain.adapter.SourceCapabilities
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey

enum class CapabilityExclusionReason {
    SORT_UNSUPPORTED,
    EXCLUDE_TAGS_UNSUPPORTED,
    DATE_RANGE_UNSUPPORTED,
    MIN_SCORE_UNSUPPORTED,
}

object SourceCapabilityGate {
    fun excludedSources(
        query: Query,
        capabilitiesBySource: Map<SourceKey, SourceCapabilities>,
    ): Map<SourceKey, Set<CapabilityExclusionReason>> {
        return capabilitiesBySource.mapNotNull { (source, capability) ->
            val reasons = mutableSetOf<CapabilityExclusionReason>()

            if (!supportsSort(query.sort, capability)) {
                reasons += CapabilityExclusionReason.SORT_UNSUPPORTED
            }
            if (query.excludeTags.isNotEmpty() && !capability.supportsExcludeTagsServerSide) {
                reasons += CapabilityExclusionReason.EXCLUDE_TAGS_UNSUPPORTED
            }
            if (query.dateRange != null && !capability.supportsDateRangeServerSide) {
                reasons += CapabilityExclusionReason.DATE_RANGE_UNSUPPORTED
            }
            if (query.minScore != null && !capability.supportsMinScoreServerSide) {
                reasons += CapabilityExclusionReason.MIN_SCORE_UNSUPPORTED
            }

            if (reasons.isEmpty()) {
                null
            } else {
                source to reasons
            }
        }.toMap()
    }

    private fun supportsSort(sortMode: SortMode, capabilities: SourceCapabilities): Boolean {
        return when (sortMode) {
            SortMode.NEWEST -> capabilities.supportsSortNewest
            SortMode.POPULAR -> capabilities.supportsSortPopular
            SortMode.TOP -> capabilities.supportsSortTop
            SortMode.RANDOM -> capabilities.supportsSortRandom
        }
    }
}
