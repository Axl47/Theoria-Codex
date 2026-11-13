package com.theoriacodex.app.applock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockSessionTest {
    @Test
    fun `a fresh run starts locked`() {
        assertFalse(AppLockSession().unlocked)
    }

    @Test
    fun `a short trip to the background keeps the app unlocked`() {
        val session = AppLockSession().apply { unlock() }
        session.onStop(nowMs = 1_000)
        session.onStart(nowMs = 1_000 + APP_LOCK_GRACE_MS)
        assertTrue(session.unlocked)
    }

    @Test
    fun `a long trip to the background locks the app again`() {
        val session = AppLockSession().apply { unlock() }
        session.onStop(nowMs = 1_000)
        session.onStart(nowMs = 1_001 + APP_LOCK_GRACE_MS)
        assertFalse(session.unlocked)
    }

    @Test
    fun `the time away is measured from the latest stop`() {
        val session = AppLockSession().apply { unlock() }
        session.onStop(nowMs = 0)
        session.onStart(nowMs = 10)
        session.onStart(nowMs = 10 * APP_LOCK_GRACE_MS)
        assertTrue(session.unlocked)
    }
}
