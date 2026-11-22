package com.theoriacodex.mihonhost

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.CheckBoxPreference
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import com.theoriacodex.domain.series.ExtensionSetting
import com.theoriacodex.domain.series.ExtensionSettingOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** What extensions rely on from the settings screen they fill, checked through the holders they call. */
@RunWith(RobolectricTestRunner::class)
class SourceSettingsTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val store: SharedPreferences = context.getSharedPreferences("source_7", Context.MODE_PRIVATE)

    @Test
    fun `a setting shows its default until a value is stored`() {
        val settings = settings { screen ->
            screen.addPreference(SwitchPreferenceCompat(screen.context).apply { key = "data"; title = "Data saver"; summary = "Smaller images"; defaultValue = true })
            screen.addPreference(EditTextPreference(screen.context).apply { key = "host"; title = "Address"; defaultValue = "https://a.test"; dialogMessage = "Without a path" })
        }

        assertEquals(
            listOf(
                ExtensionSetting.Toggle("data", "Data saver", "Smaller images", enabled = true, on = true),
                ExtensionSetting.Text("host", "Address", null, enabled = true, value = "https://a.test", dialogMessage = "Without a path"),
            ),
            settings.list(),
        )

        store.edit().putBoolean("data", false).putString("host", "https://b.test").commit()

        assertEquals(listOf(false, "https://b.test"), settings.list().map { (it as? ExtensionSetting.Toggle)?.on ?: (it as ExtensionSetting.Text).value })
    }

    @Test
    fun `a change is stored where the source reads it`() {
        val settings = settings { screen ->
            screen.addPreference(CheckBoxPreference(screen.context).apply { key = "nsfw"; defaultValue = false })
            screen.addPreference(MultiSelectListPreference(screen.context).apply { key = "ratings"; entries = arrayOf("Safe", "Adult"); entryValues = arrayOf("s", "a") })
        }
        val (nsfw, ratings) = settings.list()

        assertTrue(settings.change((nsfw as ExtensionSetting.Toggle).copy(on = true)))
        assertTrue(settings.change((ratings as ExtensionSetting.MultiChoice).copy(values = setOf("a"))))

        assertTrue(store.getBoolean("nsfw", false))
        assertEquals(setOf("a"), store.getStringSet("ratings", null))
    }

    @Test
    fun `a change listener that returns false keeps the old value`() {
        val seen = mutableListOf<Any?>()
        val settings = settings { screen ->
            screen.addPreference(
                EditTextPreference(screen.context).apply {
                    key = "host"
                    defaultValue = "https://a.test"
                    onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, value -> seen += value; (value as String).startsWith("https://") }
                },
            )
        }
        val host = settings.list().single() as ExtensionSetting.Text

        assertFalse(settings.change(host.copy(value = "ftp://b.test")))
        assertFalse(store.contains("host"))
        assertTrue(settings.change(host.copy(value = "https://b.test")))

        assertEquals(listOf<Any?>("ftp://b.test", "https://b.test"), seen)
        assertEquals("https://b.test", store.getString("host", null))
    }

    @Test
    fun `a listener can store its own text and refuse what was typed`() {
        val settings = settings { screen ->
            screen.addPreference(
                EditTextPreference(screen.context).apply {
                    key = "host"
                    onPreferenceChangeListener = Preference.OnPreferenceChangeListener { preference, value ->
                        (preference as EditTextPreference).text = (value as String).trim()
                        false
                    }
                },
            )
        }

        settings.change((settings.list().single() as ExtensionSetting.Text).copy(value = "  b.test "))

        assertEquals("b.test", (settings.list().single() as ExtensionSetting.Text).value)
    }

    @Test
    fun `a list offers its entries and its summary names the chosen one`() {
        val settings = settings { screen ->
            screen.addPreference(
                ListPreference(screen.context).apply {
                    key = "quality"
                    summary = "%s"
                    entries = arrayOf("Original", "Data saver")
                    entryValues = arrayOf("data", "data-saver")
                    defaultValue = "data"
                },
            )
        }
        val quality = settings.list().single() as ExtensionSetting.Choice

        assertEquals(listOf(ExtensionSettingOption("data", "Original"), ExtensionSettingOption("data-saver", "Data saver")), quality.options)
        assertEquals("Original", quality.summary)

        settings.change(quality.copy(value = "data-saver"))

        assertEquals("Data saver", settings.list().single().summary)
    }

    @Test
    fun `a switch describes each state, a hidden setting is left out and a disabled one cannot change`() {
        val settings = settings { screen ->
            screen.addPreference(SwitchPreferenceCompat(screen.context).apply { key = "a"; summaryOn = "Shown"; summaryOff = "Hidden"; isEnabled = false })
            screen.addPreference(SwitchPreferenceCompat(screen.context).apply { key = "b"; isVisible = false })
        }
        val only = settings.list().single() as ExtensionSetting.Toggle

        assertEquals("Hidden", only.summary)
        assertFalse(only.enabled)
        assertFalse(settings.change(only.copy(on = true)))
        assertNull(store.all["a"])
    }

    private fun settings(setup: (PreferenceScreen) -> Unit) = SourceSettings(context, ConfigurableStub(setup), store)
}
