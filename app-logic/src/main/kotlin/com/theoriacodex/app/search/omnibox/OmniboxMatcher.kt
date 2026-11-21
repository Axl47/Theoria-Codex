package com.theoriacodex.app.search.omnibox

import java.util.Locale

/** How well typed text matches an entry; earlier constants rank higher. */
private enum class OmniboxMatch { PREFIX, WORD_START, SUBSTRING }

/** Shorter text only matches the start of a word; one letter inside words matches nearly everything. */
private const val SUBSTRING_MIN_LENGTH = 2

/** Rows each kind may add to the tag suggestions. */
const val OMNIBOX_CAP_PER_KIND = 3

/**
 * Tag rows shown before the one-box matches, when there are any; the rest of the tags follow the matches.
 * Two, because about three rows fit above the keyboard and the first match must be one of them.
 */
const val TAG_ROWS_BEFORE_OMNIBOX = 2

/** The composer's suggestion rows in order: the leading tags, then the one-box [matches], then the trailing tags. */
data class TagsAroundOmnibox<T>(val leading: List<T>, val matches: List<OmniboxEntry>, val trailing: List<T>)

/**
 * Places the one-box [matches] among the tag suggestions [tags]. Tags come first, but when anything else matches
 * only the best [leadingTags] of them come before it, so the match is on screen without scrolling past every tag.
 */
fun <T> placeOmniboxMatches(
    tags: List<T>,
    matches: List<OmniboxEntry>,
    leadingTags: Int = TAG_ROWS_BEFORE_OMNIBOX,
): TagsAroundOmnibox<T> =
    if (matches.isEmpty()) TagsAroundOmnibox(tags, matches, emptyList())
    else TagsAroundOmnibox(tags.take(leadingTags), matches, tags.drop(leadingTags))

/**
 * The [entries] that [typed] reaches, best first. Matching ignores case and looks at each entry's title and
 * keywords: text that starts the title ranks above text that starts any later word, which ranks above text found
 * inside a word. Each kind keeps its best [capPerKind] matches; ties keep the kinds' order, then the input order.
 */
fun matchOmnibox(
    entries: List<OmniboxEntry>,
    typed: String,
    capPerKind: Int = OMNIBOX_CAP_PER_KIND,
): List<OmniboxEntry> {
    val needle = typed.trim().lowercase(Locale.ROOT)
    if (needle.isEmpty()) return emptyList()
    return entries
        .mapNotNull { entry -> omniboxMatch(entry, needle)?.let { match -> entry to match } }
        .sortedBy { (_, match) -> match }
        .groupBy { (entry, _) -> entry.kind }
        .flatMap { (_, matches) -> matches.take(capPerKind) }
        .sortedWith(compareBy({ (_, match) -> match }, { (entry, _) -> entry.kind }))
        .map { (entry, _) -> entry }
}

private fun omniboxMatch(entry: OmniboxEntry, needle: String): OmniboxMatch? =
    (listOf(entry.title) + entry.keywords).mapNotNull { text -> textMatch(text.lowercase(Locale.ROOT), needle) }.minOrNull()

private fun textMatch(text: String, needle: String): OmniboxMatch? {
    val index = text.indexOf(needle)
    return when {
        index < 0 -> null
        index == 0 -> OmniboxMatch.PREFIX
        startsWord(text, needle) -> OmniboxMatch.WORD_START
        needle.length >= SUBSTRING_MIN_LENGTH -> OmniboxMatch.SUBSTRING
        else -> null
    }
}

private fun startsWord(text: String, needle: String): Boolean =
    generateSequence(text.indexOf(needle)) { from -> text.indexOf(needle, from + 1).takeIf { it >= 0 } }
        .any { index -> index == 0 || !text[index - 1].isLetterOrDigit() }
