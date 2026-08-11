package com.theoriacodex.app.logic

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogicArchitectureTest {
    private val root = generateSequence(File(System.getProperty("user.dir")).absoluteFile, File::getParentFile)
        .first { directory -> File(directory, "settings.gradle.kts").isFile }

    @Test
    fun `module graph remains platform free and provider implementation independent`() {
        val build = File(root, "app-logic/build.gradle.kts").readText()
        assertTrue("implementation(project(\":core-domain\"))" in build)
        assertTrue("implementation(project(\":core-data\"))" in build)
        listOf(
            "project(\":app\")",
            "project(\":core-sources\")",
            "com.android",
            "androidx.room",
            "androidx.media3",
            "androidx.compose",
            "coil",
        ).forEach { forbidden -> assertFalse("Forbidden dependency: $forbidden", forbidden in build) }

        val forbiddenImports = listOf(
            "import android.",
            "import androidx.compose.",
            "import androidx.lifecycle.",
            "import androidx.room.",
            "import androidx.media3.",
            "import coil.",
            "import com.theoriacodex.sources.",
        )
        sourceFiles().forEach { source ->
            val text = source.readText()
            forbiddenImports.forEach { forbidden ->
                assertFalse("${source.relativeTo(root)} imports $forbidden", forbidden in text)
            }
        }

        val pixivAdapter = File(
            root,
            "core-sources/src/main/kotlin/com/theoriacodex/sources/pixiv/PixivSourceAdapter.kt",
        ).readText()
        assertTrue(
            "The provider compatibility symbol must delegate to the domain wire constant",
            "com.theoriacodex.domain.model.PIXIV_UGOIRA_MIME" in pixivAdapter,
        )
    }

    @Test
    fun `moved production owners cannot reappear in android app sources`() {
        val appSources = File(root, "app/src/main/java").walkTopDown()
            .filter(File::isFile)
            .toList()
        listOf(
            "SearchContract.kt",
            "SearchDraftReducer.kt",
            "SearchStateReducer.kt",
            "SearchExecutionContract.kt",
            "SearchScopedInputPolicy.kt",
            "SearchVisibilityFilters.kt",
            "FeedPlaybackPolicy.kt",
            "DurationFilterReadiness.kt",
            "PostMediaPolicy.kt",
            "TrainingTags.kt",
            "TagAssociation.kt",
        ).forEach { owner ->
            assertTrue("$owner must exist in app-logic", File(root, "app-logic/src/main").walk().any { it.name == owner })
            assertFalse("$owner reappeared in app", appSources.any { it.name == owner })
        }

        val scopedPolicy = File(
            root,
            "app-logic/src/main/kotlin/com/theoriacodex/app/search/SearchScopedInputPolicy.kt",
        ).readText()
        val formerOwners = listOf(
            File(root, "app/src/main/java/com/theoriacodex/app/search/SearchCoordinator.kt"),
            File(root, "app-logic/src/main/kotlin/com/theoriacodex/app/search/state/SearchDraftReducer.kt"),
        )
        listOf(
            "data class SearchScopePrefix",
            "data class ParsedScopedInput",
            "fun parseScopedInput",
            "fun resolveSupportedScope",
            "SEARCH_SCOPE_PREFIXES = mapOf",
            "const val UNIFIED_SCOPED_INPUT_BLOCKED_MESSAGE",
            "const val UNIFIED_SOURCE_TERMS_REMOVED_MESSAGE",
            "const val UNSUPPORTED_SEARCH_SCOPE_MESSAGE",
        ).forEach { declaration ->
            assertTrue("Scoped policy must own $declaration", declaration in scopedPolicy)
            formerOwners.forEach { owner ->
                assertFalse("${owner.name} duplicates $declaration", declaration in owner.readText())
            }
        }
    }

    @Test
    fun `quality gates enumerate app logic and fail closed on changed sources`() {
        val settings = File(root, "settings.gradle.kts").readText()
        val rootBuild = File(root, "build.gradle.kts").readText()
        val workflow = File(root, ".github/workflows/verify.yml").readText()
        assertTrue("include(\":app-logic\")" in settings)
        assertTrue("kover(project(\":app-logic\"))" in rootBuild)
        assertTrue(":app-logic:detektMain" in workflow)
        assertTrue("--include-module app-logic" in workflow)
        assertTrue("app-logic/build/reports/detekt/" in workflow)
    }

    @Test
    fun `app integration reuses quality upgrade source policy across delivery plans`() {
        val postMedia = File(
            root,
            "app/src/main/java/com/theoriacodex/app/media/PostMedia.kt",
        ).readText()
        val previewPlan = postMedia
            .substringAfter("fun previewMediaDeliveryPlan")
            .substringBefore("fun viewerMediaDeliveryPlan")
        val viewerPlan = postMedia
            .substringAfter("fun viewerMediaDeliveryPlan")
            .substringBefore("private fun mediaDeliveryPlan")

        assertTrue("QUALITY_UPGRADE_IMAGE_SOURCES" in previewPlan)
        assertTrue("QUALITY_UPGRADE_IMAGE_SOURCES" in viewerPlan)
        assertTrue("private val QUALITY_UPGRADE_IMAGE_SOURCES = setOf(" in postMedia)
    }

    private fun sourceFiles(): Sequence<File> = File(root, "app-logic/src/main/kotlin")
        .walkTopDown()
        .filter { file -> file.isFile && file.extension == "kt" }
}
