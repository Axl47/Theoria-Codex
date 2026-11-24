package com.theoriacodex.app.ui.navigation

import androidx.compose.runtime.staticCompositionLocalOf

fun interface HiddenTagsOpener {
    fun openHiddenTags()

    companion object {
        val None = HiddenTagsOpener { }
    }
}

val LocalHiddenTagsOpener = staticCompositionLocalOf { HiddenTagsOpener.None }
