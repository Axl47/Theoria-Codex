package com.theoriacodex.app.related

import android.util.Log

class RelatedPostsDiagnostics(route: String, owner: Any, private val log: (String) -> Unit) {
    private val label = "$route owner=${System.identityHashCode(owner).toString(16)}"

    fun record(event: String, state: RelatedPostsUiState) {
        val request = state.requestOrNull
        log(
            "$label $event state=${state.javaClass.simpleName} " +
                "source=${request?.seed?.id?.source} generation=${request?.generation} " +
                "pages=${state.availablePages.size} selected=${state.currentPageIndex}",
        )
    }

    companion object {
        val logcat: (String) -> Unit = { summary -> Log.i("RelatedPosts", summary) }
    }
}
