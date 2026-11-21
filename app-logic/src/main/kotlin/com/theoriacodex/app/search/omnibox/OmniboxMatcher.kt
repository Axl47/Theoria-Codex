package com.theoriacodex.app.search.omnibox

import java.util.Locale

/** How well typed text matches an entry; earlier constants rank higher. */
private enum class OmniboxMatch { PREFIX, WORD_START, SUBSTRING }

/** Shorter text only matches the start of a word; one letter inside words matches nearly everything. */
private const val SUBSTRING_MIN_LENGTH = 2

/** Rows each kind may add below the tag suggestions. */
const val OMNIBOX_CAP_PER_KIND = 3

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
