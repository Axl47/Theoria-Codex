package com.theoriacodex.app.recommend

import com.theoriacodex.data.repository.ForYouBlacklistEntry
import com.theoriacodex.domain.model.SourceKey

/**
 * The one rule for what the active profile's hidden tags hide: whether any entry blocks a shelf or seed of
 * [tags] on [source]. Shelf building and seed sampling both ask it. Today an entry hides exactly its own tag set.
 */
fun List<ForYouBlacklistEntry>.hidesSeed(source: SourceKey, tags: List<String>): Boolean {
    val key = blacklistKey(tags)
    return any { entry -> entry.source == source && blacklistKey(entry.tags) == key }
}

private fun blacklistKey(tags: List<String>): String =
    tags.asSequence().map { it.trim().lowercase() }.filter(String::isNotBlank).distinct().sorted().joinToString("+")
