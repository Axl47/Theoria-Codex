package com.theoriacodex.app.recommend

import com.theoriacodex.data.repository.ForYouBlacklistEntry
import com.theoriacodex.domain.model.SourceKey

/**
 * The one rule for what the active profile's hidden tags hide: whether any entry blocks a shelf or seed of
 * [tags] on [source]. Shelf building and seed sampling both ask it. An entry hides every tag set that contains
 * all of its tags, so hiding "boats" hides each shelf that includes boats.
 */
fun List<ForYouBlacklistEntry>.hidesSeed(source: SourceKey, tags: List<String>): Boolean {
    val present = blacklistTokens(tags)
    return any { entry ->
        val hidden = blacklistTokens(entry.tags)
        entry.source == source && hidden.isNotEmpty() && present.containsAll(hidden)
    }
}

private fun blacklistTokens(tags: List<String>): Set<String> =
    tags.asSequence().map { it.trim().lowercase() }.filter(String::isNotBlank).toSet()

/** Whether [seed], which may span sources, is hidden: hiding any one source's part hides the whole seed. */
fun List<ForYouBlacklistEntry>.hidesAny(seed: Map<SourceKey, List<String>>): Boolean =
    seed.any { (source, tags) -> hidesSeed(source, tags) }

/**
 * Whether a shelf of [seed] is hidden. A shelf the user [pinned] is hidden only by an entry naming exactly its
 * tags on a source, which is what Dismiss and Move to Saved write, so striking one of its tags leaves it (a
 * one-tag shelf's only tag is its exact set). Every other shelf follows [hidesAny].
 */
fun List<ForYouBlacklistEntry>.hidesShelf(seed: Map<SourceKey, List<String>>, pinned: Boolean): Boolean =
    if (!pinned) hidesAny(seed) else seed.any { (source, tags) ->
        val shelf = blacklistTokens(tags)
        any { entry -> entry.source == source && shelf.isNotEmpty() && blacklistTokens(entry.tags) == shelf }
    }
