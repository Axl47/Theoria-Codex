package com.theoriacodex.extensionhost

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.FeatureInfo
import android.content.pm.PackageInfo
import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ExtensionPackagesTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    /** A second interface, which refuses an extension whose metadata marks it as streaming by torrent. */
    private val anime = object : ExtensionKind {
        override val feature = "test.animeextension"
        override val sourceClassesKey = "test.animeextension.class"
        override val labelPrefix = "Anime: "

        override fun unsupported(info: PackageInfo): String? = "Streams by torrent".takeIf { info.applicationInfo?.metaData?.getBoolean("test.torrent") == true }

        override fun adapt(instance: Any, host: HostServices): List<HostedSource>? = error("Never loaded here")
    }

    private val packages = ExtensionPackages(context, listOf(MihonKind, anime))

    @Test
    fun `a package is listed under the kind whose feature it declares, and one declaring none is no extension`() {
        install("app.manga", MihonKind.feature, "Tachiyomi: Manga", Bundle().apply {
            putString(MihonKind.sourceClassesKey, ".MangaSource")
            putFloat("tachiyomix.extensionLib", 1.6f)
        })
        install("app.anime", anime.feature, "Anime: Anime", Bundle().apply { putString(anime.sourceClassesKey, ".AnimeSource") })
        install("app.other", "android.hardware.camera", "Other", Bundle().apply { putString(MihonKind.sourceClassesKey, ".Source") })

        val listed = packages.list()

        assertEquals(listOf("Anime", "Manga"), listed.map { it.name })
        assertSame(anime, listed[0].kind)
        assertEquals(listOf("app.anime.AnimeSource"), listed[0].sourceClasses)
        assertSame(MihonKind, listed[1].kind)
        assertEquals(listOf(null, null), listed.map { it.unsupported })
    }

    @Test
    fun `a kind's reason for refusing an extension is the extension's`() {
        install("app.torrent", anime.feature, "Anime: Torrent", Bundle().apply {
            putString(anime.sourceClassesKey, ".AnimeSource")
            putBoolean("test.torrent", true)
        })
        install("app.old", MihonKind.feature, "Tachiyomi: Old", Bundle().apply {
            putString(MihonKind.sourceClassesKey, ".MangaSource")
            putFloat("tachiyomix.extensionLib", 1.5f)
        })

        assertEquals(listOf("Needs extension API 1.5", "Streams by torrent"), packages.list().map { it.unsupported })
    }

    private fun install(packageName: String, feature: String, label: String, metaData: Bundle) {
        shadowOf(context.packageManager).installPackage(
            PackageInfo().apply {
                this.packageName = packageName
                versionName = "1.0"
                reqFeatures = arrayOf(FeatureInfo().apply { name = feature })
                applicationInfo = ApplicationInfo().apply {
                    this.packageName = packageName
                    nonLocalizedLabel = label
                    this.metaData = metaData
                    sourceDir = "/data/app/$packageName.apk"
                }
            },
        )
    }
}
