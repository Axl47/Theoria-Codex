package com.theoriacodex.app.settings.sites

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.theoriacodex.app.customsites.SiteCookieBridge
import com.theoriacodex.app.customsites.SiteCookieBridgeOwner
import com.theoriacodex.sources.http.BrowserCookieStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The verification page on the shared in-app browser: its header, status bar and Done keep Custom Sites' behavior. */
@RunWith(RobolectricTestRunner::class)
@Config(application = SiteVerificationPageTest.BridgeApplication::class, qualifiers = "w411dp-h891dp")
class SiteVerificationPageTest {
    @get:Rule val compose = createComposeRule()

    private val actions = mutableListOf<CustomSitesAction>()

    @Test
    fun `Done confirms with the browser's agent and Back cancels`() {
        compose.setContent { MaterialTheme { SiteVerificationPage(SiteVerification("site.test"), "Site", actions::add) } }

        compose.onNodeWithText(VERIFICATION_INSTRUCTION).assertExists()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithContentDescription("Back").performClick()

        assertTrue(actions.first() is CustomSitesAction.ConfirmVerification)
        assertEquals(CustomSitesAction.CancelVerification, actions.last())
    }

    class BridgeApplication : Application(), SiteCookieBridgeOwner {
        override val siteCookieBridge = SiteCookieBridge(
            object : BrowserCookieStore {
                override fun cookieHeader(url: String): String? = null

                override fun setCookie(url: String, setCookie: String) = Unit

                override fun flush() = Unit

                override fun expire(url: String, name: String?) = Unit
            },
        )
    }
}
