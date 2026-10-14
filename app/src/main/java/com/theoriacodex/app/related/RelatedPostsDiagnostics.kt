package com.theoriacodex.app.related

import android.util.Log

/** Sparse lifecycle diagnostics: no post IDs, tags, media locations or account data. */
internal class RelatedPostsDiagnostics(route: String, owner: Any) {
    private val label = "$route owner=${System.identityHashCode(owner).toString(16)}"

    fun record(event: String, state: RelatedPostsUiState, enabled: Boolean = true) {
        if (!enabled) return
        val request = state.requestOrNull
        val summary = "$label $event state=${state.javaClass.simpleName} " +
            "source=${request?.seed?.id?.source} generation=${request?.generation} " +
            "pages=${state.availablePages.size} selected=${state.currentPageIndex}"
        // Plain JVM owner tests do not provide Android's logger.
        runCatching { Log.i("RelatedPosts", summary) }
    }
}
