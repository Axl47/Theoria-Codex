package com.theoriacodex.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityIntentFilterDeviceTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun externalLinkFilterPreservesEverySupportedSchemeAndHostCombination() {
        val hosts = listOf(
            "www.pixiv.com",
            "pixiv.com",
            "www.pixiv.net",
            "pixiv.net",
            "www.gelbooru.com",
            "gelbooru.com",
            "www.nhentai.net",
            "nhentai.net",
            "www.hitomi.la",
            "hitomi.la",
            "www.iwara.tv",
            "iwara.tv",
            "rule34.xxx",
            "www.rule34.xxx",
            "rule34.paheal.net",
            "rule34video.com",
            "rule34gen.com",
        )

        listOf("http", "https").forEach { scheme ->
            hosts.forEach { host ->
                assertMainActivityMatches(
                    Intent(Intent.ACTION_VIEW, "$scheme://$host/post/42".toUri())
                        .addCategory(Intent.CATEGORY_BROWSABLE),
                )
            }
        }

        assertNull(
            resolveMainActivity(
                Intent(Intent.ACTION_VIEW, "https://example.com/post/42".toUri())
                    .addCategory(Intent.CATEGORY_BROWSABLE),
            ),
        )
    }

    @Test
    fun codexImportFilterPreservesEverySupportedSchemeAndMimeCombination() {
        listOf("content", "file").forEach { scheme ->
            listOf("application/json", "text/json").forEach { mimeType ->
                assertMainActivityMatches(
                    Intent(Intent.ACTION_VIEW).setDataAndType(
                        "$scheme://com.theoriacodex.test/import/codex.json".toUri(),
                        mimeType,
                    ),
                )
            }
        }

        assertNull(
            resolveMainActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(
                    "content://com.theoriacodex.test/import/codex.txt".toUri(),
                    "text/plain",
                ),
            ),
        )
    }

    private fun assertMainActivityMatches(intent: Intent) {
        assertEquals(MainActivity::class.java.name, resolveMainActivity(intent))
    }

    private fun resolveMainActivity(intent: Intent): String? {
        return context.packageManager.resolveActivity(
            intent.setPackage(context.packageName),
            PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.name
    }
}
