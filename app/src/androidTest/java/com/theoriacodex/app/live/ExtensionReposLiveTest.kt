package com.theoriacodex.app.live

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.theoriacodex.domain.series.InstalledExtension
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves repos on a device, through the Extensions page alone: adding Keiyoushi (whose catalog is only in its newer,
 * protobuf index) and Yūzōnō (a legacy JSON repo), updating a Keiyoushi extension an older app installed as a package,
 * installing an anime extension that was not on the device, loading its sources from the app's own storage, and
 * removing it. Runs only with `-e extensionSourceLive true`, and with `-e adoptedPackage <package>` naming a
 * Keiyoushi extension installed beforehand with `adb install` at an older version than the repo's, which it leaves
 * installed. The report is names, versions and counts only.
 */
@RunWith(AndroidJUnit4::class)
class ExtensionReposLiveTest {
    private val live = SeriesLiveDriver()
    private val device = live.device
    private val extensions get() = live.series.extensions
    private val repos get() = live.series.repos
    private val adopted: String by lazy { checkNotNull(InstrumentationRegistry.getArguments().getString(ADOPTED)) { "Name -e $ADOPTED" } }

    @Before
    fun requireExplicitOptIn() = live.requireOptIn()

    @Test
    fun extensionsAreInstalledUpdatedAndRemovedFromRepos() {
        startFromNothing()
        val system = live.context.packageManager.getPackageInfo(adopted, 0)
        live.report("adopted", "$adopted ${system.versionName} is installed as a package")

        live.openSeriesTab(By.text("No extensions on"))
        live.tap("the empty state's way to the Extensions page", By.text("Extensions"))
        live.tap("Repos", By.text("Repos"))
        addRepo(KEIYOUSHI, "Keiyoushi")
        addRepo(YUZONO, "Yūzōnō")
        val listing = runBlocking { repos.available() }
        live.report("repos", "${listing.extensions.size} extensions offered; problems: ${listing.problems}")
        assertTrue("Keiyoushi's protobuf catalog is read", listing.extensions.count { it.repo.contains("keiyoushi") } > 100)
        assertTrue("Yūzōnō's legacy catalog is read", listing.extensions.any { it.packageName == ONSEN })
        device.pressBack()

        // The package an older app installed is offered as an update, which installs a private copy in its place.
        live.waitFor("the Updates group", By.text("Updates"))
        live.tap("Update", By.text("Update"))
        val updated = live.await("the update to be installed", ::installed) { listed -> listed.singleOrNull { it.id.contains(adopted) }?.removable == true }
            .single { it.id.contains(adopted) }
        live.report("updated", "${updated.name} ${updated.versionName} (package ${system.versionName}), on=${updated.on}, listed once")
        assertFalse("An update keeps the user's choice: it was off", updated.on)
        assertTrue("The private copy is the newer one", updated.versionName != system.versionName)
        assertTrue("The package itself is left installed", runCatching { live.context.packageManager.getPackageInfo(adopted, 0) }.isSuccess)
        assertTrue("Nothing is left to update", device.wait(Until.gone(By.text("Updates")), SeriesLiveDriver.UI_TIMEOUT_MS))

        // An extension that was not on the device installs, turned on, and its sources load from the app's storage.
        live.tap("Get extensions", By.text("Get extensions"))
        live.waitFor("the search field", By.clazz("android.widget.EditText")).text = "AnimeOnsen"
        live.waitFor("AnimeOnsen in the results", By.text("AnimeOnsen"))
        live.await("the search to leave one extension to install", { device.findObjects(By.text("Install")).size }) { it == 1 }
        live.tap("Install", By.text("Install"))
        val onsen = live.await("AnimeOnsen to be installed and on", ::installed) { listed -> listed.any { it.id.contains(ONSEN) && it.on } }
            .single { it.id.contains(ONSEN) }
        assertTrue("It is kept in the app's own storage", File(onsen.id).path.startsWith(live.context.noBackupFilesDir.path))
        assertTrue("It is not an Android package", runCatching { live.context.packageManager.getPackageInfo(ONSEN, 0) }.exceptionOrNull() is PackageManager.NameNotFoundException)
        val sources = runBlocking { live.series.sources() }.map { "${it.name} [${it.language}]" }
        live.report("installed", "${onsen.name} ${onsen.versionName}, on=${onsen.on}; sources: $sources")
        assertTrue("Its sources load", sources.any { it.startsWith("AnimeOnsen") })
        device.pressBack()

        // Removing it deletes the copy and turns it off.
        live.tap("AnimeOnsen's page", By.text("AnimeOnsen"))
        live.tap("Remove", By.text("Remove"))
        live.await("AnimeOnsen to be gone", ::installed) { listed -> listed.none { it.id.contains(ONSEN) } }
        assertFalse("Its file is deleted", File(onsen.id).exists())
        assertTrue("Its sources are gone", runBlocking { live.series.sources() }.none { it.name == "AnimeOnsen" })
        live.report("removed", "AnimeOnsen is gone from the list, its file and the sources")
        startFromNothing()
    }

    /** No repos, no extension installed from one, nothing on: what a first run sees. */
    private fun startFromNothing() = runBlocking {
        extensions.installed().filter { it.removable }.forEach { repos.uninstall(it.id) }
        repos.repos().forEach { repos.remove(it.url) }
        extensions.installed().filter { it.on }.forEach { extensions.setOn(it.id, checkNotNull(it.signer), false) }
    }

    private fun addRepo(url: String, name: String) {
        live.tap("Add repo", By.text("Add repo"))
        live.waitFor("the address field", By.clazz("android.widget.EditText")).text = url
        live.tap("Add", By.text("Add"))
        live.waitFor("$name in the list", By.text(name), SeriesLiveDriver.RESULTS_TIMEOUT_MS)
    }

    private suspend fun installed(): List<InstalledExtension> = extensions.installed()

    private companion object {
        const val ADOPTED = "adoptedPackage"
        const val ONSEN = "eu.kanade.tachiyomi.animeextension.all.animeonsen"
        const val KEIYOUSHI = "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json"
        const val YUZONO = "https://raw.githubusercontent.com/yuzono/anime-repo/repo/index.min.json"
    }
}
