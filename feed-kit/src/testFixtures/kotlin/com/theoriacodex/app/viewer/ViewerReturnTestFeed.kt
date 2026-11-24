package com.theoriacodex.app.viewer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.test.junit4.ComposeTestRule
import com.theoriacodex.domain.model.PostId

class ViewerReturnTestFeed {
    private val shown = mutableStateOf(true)

    @Composable
    fun Content(content: @Composable () -> Unit) {
        val holder = rememberSaveableStateHolder()
        if (shown.value) holder.SaveableStateProvider("feed", content)
    }

    fun closeViewerOnto(compose: ComposeTestRule, transition: ViewerReturnTransition, returnTo: PostId) {
        compose.runOnIdle { shown.value = false }
        compose.runOnIdle {
            transition.begin(returnTo)
            shown.value = true
        }
        compose.waitForIdle()
    }
}
