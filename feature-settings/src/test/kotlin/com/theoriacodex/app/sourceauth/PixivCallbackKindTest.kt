package com.theoriacodex.app.sourceauth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PixivCallbackKindTest {
    @Test fun `only exact owned and provider paths identify authorization callbacks`() {
        assertEquals(PixivCallbackKind.PROVIDER_NATIVE, pixivCallbackKind("PIXIV", "account", "/login"))
        assertEquals(PixivCallbackKind.APP, pixivCallbackKind("theoriacodex", "pixiv-auth", "/callback"))
        listOf("/login-extra", "/login/extra", "/login/", "/LOGIN", null).forEach {
            assertNull(pixivCallbackKind("pixiv", "account", it))
        }
        assertNull(pixivCallbackKind("theoriacodex", "pixiv-auth", "/callback-extra"))
        assertNull(pixivCallbackKind("https", "account", "/login"))
        assertNull(pixivCallbackKind("pixiv", "other", "/login"))
    }
}
