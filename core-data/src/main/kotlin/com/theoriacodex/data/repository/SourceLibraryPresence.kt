package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.SourceKey

/**
 * Whether this device already holds library data for a source. Adding a custom site whose derived
 * id answers true asks the user before reconnecting (plan D2). Likes, Codex items and Recents all
 * reference stored posts, so the posts table answers for all of them.
 */
fun interface SourceLibraryPresence {
    suspend fun hasLibraryData(source: SourceKey): Boolean
}
