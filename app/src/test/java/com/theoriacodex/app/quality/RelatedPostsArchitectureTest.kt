package com.theoriacodex.app.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelatedPostsArchitectureTest {
    private val repositoryRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) {
        it.parentFile
    }.firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: error("Could not locate repository root")

    @Test
    fun `provider capability and shared shelf stay outside route specific source logic`() {
        val loader = source("app/src/main/java/com/theoriacodex/app/related/RelatedPostsLoader.kt")
        val search = source("app/src/main/java/com/theoriacodex/app/search/SearchScreen.kt")
        val forYou = source("app/src/main/java/com/theoriacodex/app/recommend/ForYouScreen.kt")
        val shelf = source("app/src/main/java/com/theoriacodex/app/ui/components/RelatedPostsShelf.kt")
        val grid = source("app/src/main/java/com/theoriacodex/app/ui/components/PostStaggeredGrid.kt")

        assertTrue("loader must derive support from adapter type", "is RelatedPostsSourceAdapter" in loader)
        assertTrue("Search must use shared projection", "TwoColumnProjectedPostStaggeredGrid(" in search)
        assertTrue("For You must use shared projection", "TwoColumnProjectedPostStaggeredGrid(" in forYou)
        assertTrue("related cards must converge on SearchResultCard", "SearchResultCard(" in shelf)
        assertTrue("shelf must span both staggered lanes", "StaggeredGridItemSpan.FullLine" in grid)
        assertFalse("Search must not hard-code supported providers", "SourceKey.PIXIV" in search || "SourceKey.GELBOORU" in search)
        assertFalse("For You must not hard-code supported providers", "SourceKey.PIXIV" in forYou || "SourceKey.GELBOORU" in forYou)
    }

    private fun source(path: String): String = File(repositoryRoot, path).readText()
}
