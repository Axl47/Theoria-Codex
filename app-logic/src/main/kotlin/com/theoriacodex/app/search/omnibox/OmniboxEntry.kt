package com.theoriacodex.app.search.omnibox

import com.theoriacodex.app.codex.CodexCoverCandidate

/** What an omnibox row leads to; its icon tells the kinds apart, and each kind has its own cap. */
enum class OmniboxKind { CODEX, CREATOR, SAVED_SEARCH, RECENT_SEARCH, COMMAND }

/**
 * Something besides a tag that the Search composer's typed text can reach. The app assembles the entries from
 * its own stores and binds what each one does, so Search needs nothing from the features they lead to.
 */
sealed interface OmniboxEntry {
    val kind: OmniboxKind

    /** The row's text, matched against what is typed. */
    val title: String

    /** Further words that find the entry, such as a command's synonyms. */
    val keywords: List<String>

    /**
     * Opens or runs something elsewhere; picking it closes the composer. A non-null [cover] draws a thumbnail in place
     * of the kind's icon, which stays as its fallback.
     */
    data class Action(
        override val kind: OmniboxKind,
        override val title: String,
        val detail: String? = null,
        override val keywords: List<String> = emptyList(),
        val cover: List<CodexCoverCandidate>? = null,
        val run: () -> Unit,
    ) : OmniboxEntry

    /** A preference switched in place, so the composer stays open on the results it changes. */
    data class Toggle(
        override val title: String,
        val checked: Boolean,
        override val keywords: List<String> = emptyList(),
        val set: (Boolean) -> Unit,
    ) : OmniboxEntry {
        override val kind: OmniboxKind get() = OmniboxKind.COMMAND
    }
}
