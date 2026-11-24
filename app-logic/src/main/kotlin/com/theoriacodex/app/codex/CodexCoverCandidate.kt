package com.theoriacodex.app.codex

import com.theoriacodex.domain.model.SourceKey
import java.io.File

sealed interface CodexCoverCandidate {
    data class LocalFile(val file: File, val cacheRevision: Long = 0L) : CodexCoverCandidate

    data class RemoteImage(
        val source: SourceKey,
        val url: String,
        val cacheRevision: Long = 0L,
    ) : CodexCoverCandidate
}
