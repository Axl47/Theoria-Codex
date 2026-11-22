package com.theoriacodex.app.series

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theoriacodex.domain.series.ExtensionSetting
import com.theoriacodex.domain.series.InstalledExtension
import com.theoriacodex.domain.series.SeriesExtensions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** The Extensions page: every extension on the device, null until they are first listed. Each change lists them again. */
class ExtensionsViewModel(private val extensions: SeriesExtensions) : ViewModel() {
    private val mutableState = MutableStateFlow<List<InstalledExtension>?>(null)
    val state: StateFlow<List<InstalledExtension>?> = mutableState

    /** Lists the extensions again; one may have been installed or removed while the page was away. */
    fun refresh() = then {}

    fun setOn(id: String, on: Boolean) = then { extensions.setOn(id, on) }

    fun setLanguage(id: String, language: String, on: Boolean) = then { extensions.setLanguage(id, language, on) }

    fun change(id: String, sourceId: String, setting: ExtensionSetting) = then { extensions.change(id, sourceId, setting) }

    private fun then(change: suspend () -> Unit) {
        viewModelScope.launch {
            change()
            mutableState.value = extensions.installed()
        }
    }
}
