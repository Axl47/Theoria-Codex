package com.theoriacodex.app.network

import android.app.Application
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SharedPreferencesRecentHostStoreTest {
    @Test
    fun `the saved host list opens its preferences only when first read (ADR 0102)`() {
        var opened = 0
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                opened++
                return super.getSharedPreferences(name, mode)
            }
        }
        SharedPreferencesRecentHostStore(context).save(listOf("a.example", "b.example"))
        opened = 0

        val memory = ConnectionMemory(SharedPreferencesRecentHostStore(context), activeNetwork = { null })
        assertEquals(0, opened)

        assertEquals(listOf("a.example", "b.example"), memory.recentHosts())
        assertEquals(1, opened)
    }
}
