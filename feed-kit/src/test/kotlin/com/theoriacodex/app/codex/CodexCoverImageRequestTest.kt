package com.theoriacodex.app.codex

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CodexCoverImageRequestTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `same-path named cover replacements use different image cache identities`() {
        val file = folder.newFile("cover.jpg").apply { writeText("first") }
        val context = RuntimeEnvironment.getApplication()
        val before = CodexCoverCandidate.LocalFile(file, cacheRevision = 1).toImageModel(context)
        val stamp = file.lastModified()
        file.writeText("other")
        file.setLastModified(stamp)
        val after = CodexCoverCandidate.LocalFile(file, cacheRevision = 2).toImageModel(context)
        assertEquals(before.data, after.data)
        assertNotEquals(before.memoryCacheKey, after.memoryCacheKey)
    }
}
