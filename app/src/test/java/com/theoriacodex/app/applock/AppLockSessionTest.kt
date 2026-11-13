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
    fun `a short time away keeps the app unlocked`() {
        val session = AppLockSession().apply { unlock() }
        session.onPause(nowMs = 1_000)
        session.onResume(nowMs = 1_000 + APP_LOCK_GRACE_MS)
        assertTrue(session.unlocked)
    }

    @Test
    fun `a long time away locks the app again`() {
        val session = AppLockSession().apply { unlock() }
        session.onPause(nowMs = 1_000)
        session.onResume(nowMs = 1_001 + APP_LOCK_GRACE_MS)
        assertFalse(session.unlocked)
    }

    @Test
    fun `the time away is measured from the latest pause`() {
        val session = AppLockSession().apply { unlock() }
        session.onPause(nowMs = 0)
        session.onResume(nowMs = 10)
        session.onResume(nowMs = 10 * APP_LOCK_GRACE_MS)
        assertTrue(session.unlocked)
    }
}
