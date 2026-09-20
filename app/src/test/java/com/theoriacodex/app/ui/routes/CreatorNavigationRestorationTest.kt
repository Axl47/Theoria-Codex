package com.theoriacodex.app.ui.routes

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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class CreatorNavigationRestorationTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `navigation destination still has its creator after saved instance state restoration`() {
        val restoration = StateRestorationTester(compose)
        val creator = CreatorProfile(SourceKey.PIXIV, "Illustrator", "42", "https://www.pixiv.net/users/42", "42")
        restoration.setContent {
            var pendingCreator by rememberSaveable(stateSaver = CreatorProfileStateSaver) {
                mutableStateOf<CreatorProfile?>(null)
            }
            val navigation = rememberNavController()
            NavHost(navigation, startDestination = "home") {
                composable("home") {
                    Button(onClick = { pendingCreator = creator; navigation.navigate("creator") }) { Text("Open creator") }
                }
                composable("creator") {
                    pendingCreator?.let { Text("${it.displayName}: ${it.profileId}") }
                }
            }
        }
        compose.onNodeWithText("Open creator").performClick()
        compose.onNodeWithText("Illustrator: 42").assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithText("Illustrator: 42").assertIsDisplayed()
    }

    @Test
    fun `creator navigation identity preserves native names and optional provider fields`() {
        val creator = CreatorProfile(SourceKey.HITOMI, "日本語", uploadsQuery = "artist:日本語")
        assertEquals(creator, decodeCreatorNavigationIdentity(encodeCreatorNavigationIdentity(creator)))
        assertNull(decodeCreatorNavigationIdentity(listOf("UNKNOWN", "Name", "1", "", "")))
        assertNull(decodeCreatorNavigationIdentity(emptyList()))
    }
}
