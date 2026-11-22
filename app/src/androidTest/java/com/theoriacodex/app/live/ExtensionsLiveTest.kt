package com.theoriacodex.app.live

import android.content.Context
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import com.theoriacodex.app.ui.TopLevelDestination
import com.theoriacodex.domain.series.ExtensionChoices
import com.theoriacodex.domain.series.ExtensionLanguage
import com.theoriacodex.domain.series.ExtensionSetting
import com.theoriacodex.domain.series.InstalledExtension
import com.theoriacodex.mihonhost.MihonHost
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the trust step and the Extensions page in the real debug app, with a real extension installed as a package
 * the way Mihon installs one (`adb install`, see `mihon-host/README.md`). Runs only with `-e extensionSourceLive true`.
 *
 * The tests are one story across app restarts, so run each as its own `am instrument` call, which starts the app's
 * process anew: [anInstalledExtensionRunsOnlyOnceTurnedOn], then [aChangedSettingSurvivesARestart], then
 * [anUninstalledExtensionLeavesTheSeriesTab], which removes the package, then, after installing a copy signed by
 * another key, [aCopySignedWithAnotherKeyIsOff].
 *
 * The report is names, counts and labels only, never images.
 */
@RunWith(AndroidJUnit4::class)
class ExtensionsLiveTest {
    private val live = SeriesLiveDriver()
    private val device = live.device
    private val extensions get() = live.series.extensions
    private val settings get() = live.container.data.settingsRepository

    /** What one run leaves for the next, in the app's own data. */
    private val notes by lazy { live.context.getSharedPreferences("extensions_live_test", Context.MODE_PRIVATE) }

    @Before
    fun requireExplicitOptIn() = live.requireOptIn()

    @Test
    fun anInstalledExtensionRunsOnlyOnceTurnedOn() {
        // Start from nothing turned on, before the host is asked anything.
        runBlocking { settings.updateSettings { it.copy(extensions = ExtensionChoices()) } }
        val off = installedPackage()
        live.report("installed", "${off.name} ${off.versionName}, signer ${off.signer}, on=${off.on}, languages known=${off.languages.size}")
        assertFalse("An extension is off until the user turns it on", off.on)
        assertTrue("An extension's languages come from its code, which has not run", off.languages.isEmpty())

        live.openSeriesTab(By.text("No extensions on"))
        assertTrue("The Series tab offers no source", runBlocking { live.series.sources() }.isEmpty())
        assertEquals("No extension code was loaded", 0, loadedLines().size)
        live.report("off", "Series tab shows \"No extensions on\", sources=0, extensions loaded=0")

        live.tap("the empty state's way to the Extensions page", By.text("Extensions"))
        live.tap("${off.name}'s switch", switchOf(off))
        live.waitFor("what turning an extension on means", By.textContains("can read this app's data"))
        live.waitFor("the signer in the question", By.textContains(checkNotNull(off.signer)))
        live.tap("Turn on", By.text("Turn on"))
        val on = live.await("the extension to be on", ::installedPackage) { it.on && it.languages.isNotEmpty() }
        live.waitFor("the switch to read as on", switchOf(on).checked(true))

        val languages = on.languages.filter(ExtensionLanguage::on).map(ExtensionLanguage::code)
        val sources = runBlocking { live.series.sources() }
        live.report("on", "${on.languages.size} languages, on: $languages; sources: ${sources.map { "${it.name} [${it.language}]" }}; loaded: ${loadedLines()}")
        assertEquals("Only the device's language is on", listOf("en"), languages)
        assertEquals("Only that language's source is offered", listOf("en"), sources.map { it.language })
        // A second copy of the same extension, as a file in the debug folder, is another extension to turn on.
        val others = runBlocking { extensions.installed() }.filter { it.id != on.id }
        live.report("others", others.joinToString { "${it.name} on=${it.on}" }.ifEmpty { "none on the device" })
        assertTrue("Turning one extension on turns on no other", others.none { it.on })
        assertEquals("Only the extension turned on was loaded", 1, loadedLines().size)

        live.tap("the extension's page", By.text(on.name))
        val listed = settingsOf(on).single().settings
        live.report("settings", "${listed.size} listed: ${listed.joinToString { "${it.title} (${it::class.simpleName})" }}")
        val toggle = listed.filterIsInstance<ExtensionSetting.Toggle>().firstOrNull { it.enabled } ?: error("The extension lists no switch to change")
        showing(toggle.title).click()
        live.await("the setting to change", { setting(toggle.key) }) { it?.on == !toggle.on }
        notes.edit().putString(CHANGED_KEY, toggle.key).putBoolean(CHANGED_VALUE, !toggle.on).commit()
        live.report("changed", "\"${toggle.title}\" from ${toggle.on} to ${!toggle.on}")

        device.pressBack()
        device.pressBack()
        val first = runBlocking { sources.single().popular(1) }.series.first()
        live.waitFor("the Series tab's source list", By.text("Browse"), SeriesLiveDriver.RESULTS_TIMEOUT_MS)
        live.waitFor("the first series the source lists", By.text(first.title), SeriesLiveDriver.RESULTS_TIMEOUT_MS)
        live.report("browse", "the tab lists \"${first.title}\" from ${sources.single().name} without a restart")
    }

    @Test
    fun aChangedSettingSurvivesARestart() {
        val key = checkNotNull(notes.getString(CHANGED_KEY, null)) { "Run anInstalledExtensionRunsOnlyOnceTurnedOn first" }
        val expected = notes.getBoolean(CHANGED_VALUE, false)
        val on = installedPackage()
        assertTrue("The extension is still on after the restart", on.on)

        val stored = checkNotNull(setting(key)) { "The setting is no longer listed" }
        assertEquals("The changed setting kept its value", expected, stored.on)
        // The value is in the source's own preferences file, which the host names apart from the app's files.
        val sourceId = settingsOf(on).single().sourceId.substringAfterLast('_')
        val file = live.context.getSharedPreferences("${MihonHost.PREFERENCES_PREFIX}source_$sourceId", Context.MODE_PRIVATE)
        assertEquals("The source's preferences file holds the value", expected, file.all[key])
        live.report("restart", "\"${stored.title}\" is still $expected, kept in ${MihonHost.PREFERENCES_PREFIX}source_$sourceId")

        live.openSeriesTab()
        live.tap("the Extensions button", By.desc("Extensions"))
        live.tap("the extension's page", By.text(on.name))
        assertEquals("The page shows the kept value", expected, showing(stored.title).parentOrSelfCheckable().isChecked)
        live.report("page", "the Extensions page shows \"${stored.title}\" ${if (expected) "on" else "off"}")
        live.context.deleteSharedPreferences("extensions_live_test")
    }

    /** Removes the package while the app runs; the tab drops its source the next time it is shown, without a restart. */
    @Test
    fun anUninstalledExtensionLeavesTheSeriesTab() {
        val on = installedPackage()
        assertTrue("The extension is on", on.on)
        live.openSeriesTab()
        val before = runBlocking { live.series.sources() }.map { it.name }
        // The package's name is the last part of its install folder, before the suffix Android adds.
        val packageName = on.id.substringBeforeLast('/').substringAfterLast('/').substringBeforeLast('-')
        val answer = device.executeShellCommand("pm uninstall $packageName").trim()
        live.report("uninstall", "$packageName: $answer; sources before: $before")

        live.tap("another tab", By.desc(TopLevelDestination.Library.label))
        live.tap("the Series tab again", By.desc(TopLevelDestination.Series.label))
        live.waitFor("the tab without the extension's source", By.text("No extensions on"), SeriesLiveDriver.RESULTS_TIMEOUT_MS)
        assertTrue("No source is offered", runBlocking { live.series.sources() }.isEmpty())
        live.report("after", "the Series tab shows \"No extensions on\", sources=0, same process")
    }

    @Test
    fun aCopySignedWithAnotherKeyIsOff() {
        val trusted = runBlocking { settings.observeSettings().first() }.extensions.trusted.values
        assertTrue("A signer was turned on before the package was replaced", trusted.isNotEmpty())
        val copy = installedPackage()
        live.report("copy", "${copy.name} ${copy.versionName}, signer ${copy.signer}, on=${copy.on}; turned on earlier: $trusted")
        assertNotEquals("The copy is signed with another key", trusted.single(), copy.signer)
        assertFalse("A copy signed with another key is off", copy.on)

        live.openSeriesTab(By.text("No extensions on"))
        assertTrue("The Series tab offers no source", runBlocking { live.series.sources() }.isEmpty())
        assertEquals("The copy's code was not loaded", 0, loadedLines().size)
        live.tap("the Extensions button", By.desc("Extensions"))
        live.waitFor("the copy's switch, off", switchOf(copy).checked(false))
        live.report("copy", "the Series tab shows \"No extensions on\", its switch is off, extensions loaded=0")
    }

    /** The extension installed as a package, as the page lists it; `-e extensionName <name>` picks one when several are. */
    private fun installedPackage(): InstalledExtension {
        val name = InstrumentationRegistry.getArguments().getString("extensionName")
        return runBlocking { extensions.installed() }.firstOrNull { it.id.startsWith("/data/app/") && (name == null || it.name == name) }
            ?: error("No extension${name?.let { " named $it" }.orEmpty()} is installed as a package")
    }

    /** An extension's switch in the list. Compose gives a named toggle its name as a child element. */
    private fun switchOf(extension: InstalledExtension) = By.checkable(true).hasDescendant(By.desc(extension.name))

    private fun setting(key: String): ExtensionSetting.Toggle? =
        settingsOf(installedPackage()).flatMap { it.settings }.firstOrNull { it.key == key } as? ExtensionSetting.Toggle

    private fun settingsOf(extension: InstalledExtension) = runBlocking { extensions.settings(extension.id) }

    /** The row titled [title], scrolled into view when the page is longer than the screen. Settings arrive a moment after their page opens. */
    private fun showing(title: String) = device.wait(Until.findObject(By.text(title)), SeriesLiveDriver.SHORT_WAIT_MS)
        ?: device.findObject(By.scrollable(true))?.scrollUntil(Direction.DOWN, Until.findObject(By.text(title)))
        ?: error("\"$title\" is not on the page; on screen: ${live.screenText()}")

    /** A switch row is one checkable element; its title can be that element or a child of it. */
    private fun androidx.test.uiautomator.UiObject2.parentOrSelfCheckable() = generateSequence(this) { it.parent }.first { it.isCheckable }

    /** The host's "Loaded <extension>" log lines since this process started: one per extension whose code ran. */
    private fun loadedLines(): List<String> =
        ProcessBuilder("logcat", "-d", "--pid=${Process.myPid()}", "-s", "MihonHost:I").start().inputStream.bufferedReader().readLines()
            .filter { ": Loaded " in it }
            .map { it.substringAfter(": Loaded ").substringBefore(", signer") }

    private companion object {
        const val CHANGED_KEY = "key"
        const val CHANGED_VALUE = "value"
    }
}
