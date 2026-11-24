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

    private val packages = ExtensionPackages(context, listOf(MihonKind, AniyomiKind))

    @Test
    fun `a package is listed under the kind whose feature it declares, and one declaring none is no extension`() {
        install("app.manga", MihonKind.feature, "Tachiyomi: Manga", Bundle().apply {
            putString(MihonKind.sourceClassesKey, ".MangaSource")
            putFloat("tachiyomix.extensionLib", 1.6f)
        })
        install("app.anime", AniyomiKind.feature, "Aniyomi: Anime", Bundle().apply { putString(AniyomiKind.sourceClassesKey, ".AnimeSource") }, version = "14.25")
        install("app.other", "android.hardware.camera", "Other", Bundle().apply { putString(MihonKind.sourceClassesKey, ".Source") })

        val listed = packages.list()

        assertEquals(listOf("Anime", "Manga"), listed.map { it.name })
        assertSame(AniyomiKind, listed[0].kind)
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
    fun `anime extensions of interface 14 and 16 are accepted, and any other version, an unreadable one or a torrent one is refused`() {
        listOf("a" to "14.64", "b" to "16.8", "c" to "17.1", "d" to "13.9", "da" to "beta").forEach { (name, version) ->
            install("app.$name", AniyomiKind.feature, "Aniyomi: $name", Bundle().apply { putString(AniyomiKind.sourceClassesKey, ".Source") }, version)
        }
        install("app.e", AniyomiKind.feature, "Aniyomi: e", Bundle().apply {
            putString(AniyomiKind.sourceClassesKey, ".Source")
            putInt("tachiyomi.animeextension.torrent", 1)
        }, version = "16.1")

        assertEquals(
            listOf(null, null, "Needs extension API 17", "Needs extension API 13", "Unknown extension API", "Streams by torrent"),
            packages.list().map { it.unsupported },
        )
    }

    @Test
    fun `a private copy stands in for a package of its name, unless the other app updated it past the copy`() {
        fun extension(name: String, version: Long, signer: String, origin: ExtensionOrigin) =
            ExtensionPackage(name, name, "1.6.$version", version, signer, java.io.File("/$origin/$name.apk"), origin, MihonKind, listOf(".S"), size = 1, modified = 1)
        val owned = listOf("same", "newer", "foreign").map { extension(it, 5, "key", ExtensionOrigin.PRIVATE) } + extension("alone", 1, "key", ExtensionOrigin.PRIVATE)
        val installed = listOf(extension("same", 5, "key", ExtensionOrigin.PACKAGE), extension("newer", 6, "key", ExtensionOrigin.PACKAGE), extension("foreign", 9, "other", ExtensionOrigin.PACKAGE))

        assertEquals(
            mapOf("same" to ExtensionOrigin.PRIVATE, "foreign" to ExtensionOrigin.PRIVATE, "alone" to ExtensionOrigin.PRIVATE, "newer" to ExtensionOrigin.PACKAGE),
            standing(owned, installed).associate { it.packageName to it.origin },
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
