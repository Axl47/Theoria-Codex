package com.theoriacodex.app.source

import android.app.Application
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.theoriacodex.app.codex.CodexCoverCandidate
import com.theoriacodex.app.codex.resolveNamedCodexCoverCandidates
import com.theoriacodex.app.ui.routes.decodeCreatorNavigationIdentity
import com.theoriacodex.app.ui.routes.encodeCreatorNavigationIdentity
import com.theoriacodex.app.viewer.decodeViewerPostId
import com.theoriacodex.app.viewer.encodeViewerPostId
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** App-side string forms of a source must carry custom ids, which are not enum names. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SourceIdentityEncodingTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val folder = TemporaryFolder()

    private val custom = requireNotNull(SourceKey.parse("custom_safebooru_org"))

    @Test
    fun `a selected source survives saved instance state`() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            var selected by rememberSaveable(stateSaver = NullableSourceKeySaver) { mutableStateOf<SourceKey?>(null) }
            Button(onClick = { selected = custom }) { Text("Select") }
            Text("Selected: ${selected?.id}")
        }
        compose.onNodeWithText("Select").performClick()

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithText("Selected: custom_safebooru_org").assertIsDisplayed()
    }

    @Test
    fun `viewer and creator restoration encodings keep custom ids`() {
        val postId = PostId(custom, "123")
        assertEquals(postId, decodeViewerPostId(encodeViewerPostId(postId)))

        val creator = CreatorProfile(custom, "uploader", "uploader", null, "user:uploader")
        assertEquals(creator, decodeCreatorNavigationIdentity(encodeCreatorNavigationIdentity(creator)))
    }

    @Test
    fun `named Codex covers are found for built-in and custom sources`() {
        val thumbnails = folder.newFolder("cache", "thumbnails")
        val customCover = thumbnails.resolve("codex-1_custom_safebooru_org.jpg").apply { writeBytes(byteArrayOf(1)) }
        val pixivCover = thumbnails.resolve("codex-1_PIXIV.png").apply { writeBytes(byteArrayOf(1)) }
        thumbnails.resolve("codex-10_PIXIV.png").writeBytes(byteArrayOf(1))

        val candidates = resolveNamedCodexCoverCandidates(folder.root, "codex-1")

        assertEquals(
            listOf(CodexCoverCandidate.LocalFile(pixivCover), CodexCoverCandidate.LocalFile(customCover)),
            candidates,
        )
    }
}
