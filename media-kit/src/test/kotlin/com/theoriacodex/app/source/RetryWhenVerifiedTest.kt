package com.theoriacodex.app.source

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.http.HostOutageKind
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RetryWhenVerifiedTest {
    @get:Rule val compose = createComposeRule()

    private val passes = MutableSharedFlow<SourceKey>(extraBufferCapacity = 4)
    private var issues by mutableStateOf(mapOf(SourceKey.IWARA to HostOutageKind.CHALLENGE))
    private val retried = mutableListOf<SourceKey>()

    @Test
    fun `a challenge that merely expires retries nothing, and a passed check retries the source`() {
        compose.setContent {
            CompositionLocalProvider(LocalSourceHealth provides SourceHealth(issues = issues, passes = passes)) {
                retryWhenVerified(listOf(SourceKey.IWARA, SourceKey.GELBOORU)) { retried += it }
            }
        }
        compose.waitForIdle()

        issues = emptyMap()
        compose.waitForIdle()
        assertEquals(emptyList<SourceKey>(), retried)

        passes.tryEmit(SourceKey.GELBOORU)
        passes.tryEmit(SourceKey.IWARA)
        compose.waitForIdle()
        assertEquals(listOf(SourceKey.IWARA), retried)
    }
}
