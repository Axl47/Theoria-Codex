package com.theoriacodex.app.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.search.SearchResultCard
import com.theoriacodex.app.search.searchMediaTestTag
import com.theoriacodex.app.testing.testPost
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class PostShelfSizingTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `portrait and landscape previews share the shelf height at both card widths`() {
        val widths = listOf(164.dp, 180.dp)
        val rows = widths.map { width ->
            listOf(
                testPost(sourcePostId = "tall-$width", width = 100, height = 1000),
                testPost(sourcePostId = "wide-$width", width = 1000, height = 100),
            )
        }
        compose.setContent {
            MaterialTheme {
                Column {
                    widths.zip(rows).forEach { (width, posts) ->
                        LazyRow(Modifier.height(rememberPostShelfHeight(posts, width))) {
                            items(posts) { post ->
                                Box(Modifier.width(width)) {
                                    SearchResultCard(post, pixivUgoiraClient = null,
                                        mediaAspectRatio = POST_SHELF_MEDIA_ASPECT_RATIO, onClick = {})
                                }
                            }
                        }
                    }
                }
            }
        }
        widths.zip(rows).forEach { (width, posts) ->
            posts.forEach { post ->
                compose.onNodeWithTag(searchMediaTestTag(post.id), useUnmergedTree = true)
                    .assertHeightIsEqualTo(width)
            }
        }
    }
}
