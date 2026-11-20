package com.theoriacodex.app.viewer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.test.junit4.ComposeTestRule
import com.theoriacodex.domain.model.PostId

/**
 * Hosts a feed the way NavHost does under the Viewer: it leaves composition while the Viewer is open and
 * returns with its saved state, so its cards report fresh bounds to the returning media.
 */
class ViewerReturnTestFeed {
    private val shown = mutableStateOf(true)

    @Composable
    fun Content(content: @Composable () -> Unit) {
        val holder = rememberSaveableStateHolder()
        if (shown.value) holder.SaveableStateProvider("feed", content)
    }

    /** Opens the Viewer over the feed, then closes it onto [returnTo]. */
    fun closeViewerOnto(compose: ComposeTestRule, transition: ViewerReturnTransition, returnTo: PostId) {
        compose.runOnIdle { shown.value = false }
        compose.runOnIdle {
            transition.begin(returnTo)
            shown.value = true
        }
        compose.waitForIdle()
    }
}
