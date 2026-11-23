package com.theoriacodex.mihonhost

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ExtensionPackagesTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun `the stamp moves when a package changes and holds while none does`() {
        val packages = ExtensionPackages(context)
        val before = packages.stamp()
        assertEquals(before, packages.stamp())

        // Robolectric files a change under the number it is asked from.
        shadowOf(context.packageManager).addChangedPackage(0, "eu.kanade.tachiyomi.extension.en.site")
        val after = packages.stamp()
        assertNotEquals(before, after)
        assertEquals(after, packages.stamp())
    }
}
