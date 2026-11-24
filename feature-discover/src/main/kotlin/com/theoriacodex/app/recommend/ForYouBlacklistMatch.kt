package com.theoriacodex.app.recommend

import com.theoriacodex.data.repository.ForYouBlacklistEntry
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.tags.normalizeMatchToken

fun List<ForYouBlacklistEntry>.hidesSeed(source: SourceKey, tags: List<String>): Boolean {
    val present = blacklistTokens(tags)
    return any { entry ->
        val hidden = blacklistTokens(entry.tags)
        entry.source == source && hidden.isNotEmpty() && present.containsAll(hidden)
    }
}

private fun blacklistTokens(tags: List<String>): Set<String> =
    tags.asSequence().map(::normalizeMatchToken).filter(String::isNotBlank).toSet()
