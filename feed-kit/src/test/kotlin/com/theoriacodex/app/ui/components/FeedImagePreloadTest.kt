package com.theoriacodex.app.ui.components

import androidx.compose.foundation.lazy.staggeredgrid.items
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import coil.Coil
import coil.EventListener
import coil.ImageLoader
import coil.fetch.Fetcher
import coil.request.ImageRequest
import com.theoriacodex.app.testing.testPost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkInfo

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FeedImagePreloadTest {
    @get:Rule val compose = createComposeRule()

    private val preloaded = mutableListOf<String>()

    @Before fun installLoader() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        shadowOf(manager).setActiveNetworkInfo(
            ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, NetworkInfo.State.CONNECTED),
        )
        Coil.setImageLoader(
            ImageLoader.Builder(context)
                .dispatcher(Dispatchers.Unconfined)
                .components { add(Fetcher.Factory<Any> { _, _, _ -> Fetcher { awaitCancellation() } }) }
                .eventListener(object : EventListener {
                    override fun onStart(request: ImageRequest) {
                        if (request.parameters.value<Boolean>("theoria.feedPreload") == true) preloaded += request.data.toString()
                    }
                })
                .build(),
        )
    }

    @After fun resetCoil() = Coil.reset()

    @Test fun `a plain post grid preloads the card images just past what it shows`() {
        val posts = List(40) { testPost(sourcePostId = "p$it") }
        compose.setContent {
            // Recents' grid holds headings among its posts; any grid built on AdaptivePostGrid preloads like every feed.
            AdaptivePostGrid(posts, rememberLazyStaggeredGridState(), Modifier.width(360.dp).height(400.dp)) {
                items(posts.size) { Box(Modifier.height(100.dp)) }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(12, preloaded.size)
            assertEquals(preloaded.distinct(), preloaded)
        }
    }
}
