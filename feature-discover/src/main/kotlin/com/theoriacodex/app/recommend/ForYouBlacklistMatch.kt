package com.theoriacodex.app.recommend

import com.theoriacodex.data.repository.ForYouBlacklistEntry
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.tags.normalizeMatchToken

/**
 * The one rule for what the active profile's hidden tags hide: whether any entry blocks a lane of [tags] on [source].
 * An entry hides every tag set that contains all of its tags, so hiding "boats" hides each lane that includes boats.
 */
fun List<ForYouBlacklistEntry>.hidesSeed(source: SourceKey, tags: List<String>): Boolean {
    val present = blacklistTokens(tags)
    return any { entry ->
        val hidden = blacklistTokens(entry.tags)
        entry.source == source && hidden.isNotEmpty() && present.containsAll(hidden)
    }
}

/** Spelled as the cards' hidden tags are, so a lane and its posts are hidden by the same entry. */
private fun blacklistTokens(tags: List<String>): Set<String> =
    tags.asSequence().map(::normalizeMatchToken).filter(String::isNotBlank).toSet()
