package com.theoriacodex.app.search.omnibox

import com.theoriacodex.app.codex.CodexCoverCandidate

enum class OmniboxKind { CODEX, SERIES, CREATOR, SAVED_SEARCH, RECENT_SEARCH, COMMAND }

sealed interface OmniboxEntry {
    val kind: OmniboxKind

    val title: String

    val keywords: List<String>

    data class Action(
        override val kind: OmniboxKind,
        override val title: String,
        val detail: String? = null,
        override val keywords: List<String> = emptyList(),
        val cover: List<CodexCoverCandidate>? = null,
        val run: () -> Unit,
    ) : OmniboxEntry

    data class Toggle(
        override val title: String,
        val checked: Boolean,
        override val keywords: List<String> = emptyList(),
        val set: (Boolean) -> Unit,
    ) : OmniboxEntry {
        override val kind: OmniboxKind get() = OmniboxKind.COMMAND
    }
}
