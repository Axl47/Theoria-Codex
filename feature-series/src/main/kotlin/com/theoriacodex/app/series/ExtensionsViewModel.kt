package com.theoriacodex.app.series

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theoriacodex.domain.series.ExtensionSetting
import com.theoriacodex.domain.series.ExtensionSourceSettings
import com.theoriacodex.domain.series.InstalledExtension
import com.theoriacodex.domain.series.SeriesExtensions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The Extensions page. [extensions] is every extension on the device, null until they are first listed. [openId] is
 * the extension whose own page shows and [settings] that extension's settings, null while they load. [asking] is the
 * extension the user is being asked to turn on, with the signer the question shows.
 */
data class ExtensionsState(
    val extensions: List<InstalledExtension>? = null,
    val openId: String? = null,
    val settings: List<ExtensionSourceSettings>? = null,
    val asking: InstalledExtension? = null,
)

class ExtensionsViewModel(private val extensions: SeriesExtensions) : ViewModel() {
    private val mutableState = MutableStateFlow(ExtensionsState())
    val state: StateFlow<ExtensionsState> = mutableState

    init {
        // Lists the extensions now and again whenever one is installed, removed or switched while the page shows.
        viewModelScope.launch { extensions.revision.collect { show(extensions.installed()) } }
    }

    /** Lists the extensions again on return to the page: a file put in the debug folder sends no signal. */
    fun refresh() {
        viewModelScope.launch { show(extensions.installed()) }
    }

    /** Asks before turning [extension] on; null withdraws the question. */
    fun ask(extension: InstalledExtension?) = mutableState.update { it.copy(asking = extension) }

    /**
     * Turns [extension] alone on or off, as the signer the user saw. When its file was replaced while the question was open
     * it stays off, and the question is asked again about what is there now.
     */
    fun setOn(extension: InstalledExtension, on: Boolean) {
        val shown = extension.signer ?: return
        mutableState.update { it.copy(asking = null) }
        viewModelScope.launch {
            val listed = extensions.setOn(extension.id, shown, on)
            show(listed)
            val now = listed.firstOrNull { it.id == extension.id }
            if (on && now != null && !now.on && now.signer != null && now.signer != shown) ask(now)
        }
    }

    fun setLanguage(id: String, language: String, on: Boolean) {
        viewModelScope.launch {
            show(extensions.setLanguage(id, language, on))
            showSettings(id) { extensions.settings(id) }
        }
    }

    /** Opens [id]'s own page, or with null goes back to the list; only that extension's settings are read. */
    fun open(id: String?) {
        mutableState.update { it.copy(openId = id, settings = null) }
        if (id != null) viewModelScope.launch { showSettings(id) { extensions.settings(id) } }
    }

    fun change(id: String, sourceId: String, setting: ExtensionSetting) {
        viewModelScope.launch { showSettings(id) { extensions.change(id, sourceId, setting) } }
    }

    private fun show(listed: List<InstalledExtension>) = mutableState.update { it.copy(extensions = listed) }

    private suspend fun showSettings(id: String, read: suspend () -> List<ExtensionSourceSettings>) {
        val settings = read()
        mutableState.update { if (it.openId == id) it.copy(settings = settings) else it }
    }
}
