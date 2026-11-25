package com.theoriacodex.app.fixtures

import android.os.Bundle
import android.view.WindowManager
import android.app.PictureInPictureParams
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.viewmodel.compose.viewModel
import com.theoriacodex.app.appshell.AppShellViewModel
import com.theoriacodex.app.appshell.ViewerSessionRetentionViewModel
import com.theoriacodex.app.di.TheoriaAppContainer
import com.theoriacodex.app.ui.TheoriaAppContent
import com.theoriacodex.app.viewer.ViewerAutoPictureInPicture
import com.theoriacodex.app.viewer.ViewerHostActivity
import com.theoriacodex.app.viewer.ViewerRotationState

@OptIn(ExperimentalComposeUiApi::class)
class JourneyTestActivity : ViewerHostActivity() {
    override val viewerPictureInPictureActive = false
    override val viewerAutoPictureInPicture = ViewerAutoPictureInPicture(this)
    override val viewerRotation = ViewerRotationState()
    override val keepViewerPlayingOnPause = false

    override fun requestViewerPictureInPicture(params: PictureInPictureParams): Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val container = checkNotNull(fixtureContainer) { "Install a fixture before launching the journey host" }
        setContent {
            Box(Modifier.semantics { testTagsAsResourceId = true }) {
                TheoriaAppContent(
                    appContainer = container,
                    viewerSessionOwner = viewModel<ViewerSessionRetentionViewModel>(),
                    appShellOwner = viewModel<AppShellViewModel>(),
                )
            }
        }
    }

    companion object {
        @Volatile var fixtureContainer: TheoriaAppContainer? = null
    }
}
