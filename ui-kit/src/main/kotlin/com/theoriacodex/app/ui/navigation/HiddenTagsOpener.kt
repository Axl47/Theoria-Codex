package com.theoriacodex.app.ui.navigation

import androidx.compose.runtime.staticCompositionLocalOf

/** Opens the active profile's hidden tags, the one sheet the shell hosts. Read from [LocalHiddenTagsOpener]. */
fun interface HiddenTagsOpener {
    fun openHiddenTags()

    companion object {
        val None = HiddenTagsOpener { }
    }
}

val LocalHiddenTagsOpener = staticCompositionLocalOf { HiddenTagsOpener.None }
