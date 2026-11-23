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

    private val packages = ExtensionPackages(context, listOf(MihonKind, AnimeKind))

    @Test
    fun `a package is listed under the kind whose feature it declares, and one declaring none is no extension`() {
        install("app.manga", MihonKind.feature, "Tachiyomi: Manga", Bundle().apply {
            putString(MihonKind.sourceClassesKey, ".MangaSource")
            putFloat("tachiyomix.extensionLib", 1.6f)
        })
        install("app.anime", AnimeKind.feature, "Aniyomi: Anime", Bundle().apply { putString(AnimeKind.sourceClassesKey, ".AnimeSource") }, version = "14.25")
        install("app.other", "android.hardware.camera", "Other", Bundle().apply { putString(MihonKind.sourceClassesKey, ".Source") })

        val listed = packages.list()

        assertEquals(listOf("Anime", "Manga"), listed.map { it.name })
        assertSame(AnimeKind, listed[0].kind)
        assertEquals(listOf("app.anime.AnimeSource"), listed[0].sourceClasses)
        assertSame(MihonKind, listed[1].kind)
        assertEquals(listOf(null, null), listed.map { it.unsupported })
    }

    @Test
    fun `a kind's reason for refusing an extension is the extension's`() {
        install("app.old", MihonKind.feature, "Tachiyomi: Old", Bundle().apply {
            putString(MihonKind.sourceClassesKey, ".MangaSource")
            putFloat("tachiyomix.extensionLib", 1.5f)
        })

        assertEquals(listOf("Needs extension API 1.5"), packages.list().map { it.unsupported })
    }

    @Test
    fun `anime extensions of interface 14 and 16 are accepted, and any other version or a torrent one is refused`() {
        listOf("a" to "14.64", "b" to "16.8", "c" to "17.1", "d" to "13.9").forEach { (name, version) ->
            install("app.$name", AnimeKind.feature, "Aniyomi: $name", Bundle().apply { putString(AnimeKind.sourceClassesKey, ".Source") }, version)
        }
        install("app.e", AnimeKind.feature, "Aniyomi: e", Bundle().apply {
            putString(AnimeKind.sourceClassesKey, ".Source")
            putInt("tachiyomi.animeextension.torrent", 1)
        }, version = "16.1")

        assertEquals(
            listOf(null, null, "Needs extension API 17", "Needs extension API 13", "Streams by torrent"),
            packages.list().map { it.unsupported },
        )
    }

    private fun install(packageName: String, feature: String, label: String, metaData: Bundle, version: String = "1.0") {
        shadowOf(context.packageManager).installPackage(
            PackageInfo().apply {
                this.packageName = packageName
                versionName = version
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
