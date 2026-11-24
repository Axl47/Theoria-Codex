package com.theoriacodex.app.series

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theoriacodex.domain.series.ExtensionRepo
import com.theoriacodex.domain.series.ExtensionRepos
import com.theoriacodex.domain.series.ExtensionSetting
import com.theoriacodex.domain.series.ExtensionSourceSettings
import com.theoriacodex.domain.series.InstalledExtension
import com.theoriacodex.domain.series.NewExtensionRepo
import com.theoriacodex.domain.series.RepoExtension
import com.theoriacodex.domain.series.RepoListing
import com.theoriacodex.domain.series.SeriesExtensions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ExtensionsSubpage { REPOS, BROWSE }

data class AddRepo(val busy: Boolean = false, val problem: String? = null)

data class ExtensionsState(
    val extensions: List<InstalledExtension>? = null,
    val openId: String? = null,
    val settings: List<ExtensionSourceSettings>? = null,
    val asking: InstalledExtension? = null,
    val subpage: ExtensionsSubpage? = null,
    val repos: List<ExtensionRepo>? = null,
    val listing: RepoListing? = null,
    val installing: Set<String> = emptySet(),
    val failures: Map<String, String> = emptyMap(),
    val addRepo: AddRepo? = null,
) {
    val updates: List<RepoExtension> get() = listing?.extensions.orEmpty().filter { it.status == RepoExtension.Status.UPDATE }
}

class ExtensionsViewModel(private val extensions: SeriesExtensions, private val repos: ExtensionRepos) : ViewModel() {
    private val mutableState = MutableStateFlow(ExtensionsState())
    val state: StateFlow<ExtensionsState> = mutableState

    init {
        viewModelScope.launch {
            extensions.revision.collect {
                show(extensions.installed())
                if (mutableState.value.listing != null) showListing(refresh = false)
            }
        }
        viewModelScope.launch { showListing(refresh = true) }
    }

    fun refresh() {
        viewModelScope.launch { show(extensions.installed()) }
    }

    fun ask(extension: InstalledExtension?) = mutableState.update { it.copy(asking = extension) }

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

    fun open(id: String?) {
        mutableState.update { it.copy(openId = id, settings = null) }
        if (id != null) viewModelScope.launch { showSettings(id) { extensions.settings(id) } }
    }

    fun change(id: String, sourceId: String, setting: ExtensionSetting) {
        viewModelScope.launch { showSettings(id) { extensions.change(id, sourceId, setting) } }
    }

    fun openSubpage(subpage: ExtensionsSubpage?) = mutableState.update { it.copy(subpage = subpage) }

    fun askForRepo(open: Boolean) = mutableState.update { it.copy(addRepo = if (open) AddRepo() else null) }

    fun addRepo(url: String) {
        mutableState.update { it.copy(addRepo = AddRepo(busy = true)) }
        viewModelScope.launch {
            val problem = repos.add(listOf(NewExtensionRepo(url))).values.firstOrNull()
            mutableState.update { it.copy(addRepo = problem?.let { reason -> AddRepo(problem = reason) }) }
            if (problem == null) showListing(refresh = false)
        }
    }

    fun removeRepo(url: String) {
        viewModelScope.launch {
            repos.remove(url)
            showListing(refresh = false)
        }
    }

    fun install(extension: RepoExtension) {
        viewModelScope.launch { installOne(extension) }
    }

    fun updateAll() {
        val updates = mutableState.value.updates
        viewModelScope.launch { updates.forEach { installOne(it) } }
    }

    fun uninstall(extension: InstalledExtension) {
        open(null)
        viewModelScope.launch { repos.uninstall(extension.id) }
    }

    private suspend fun installOne(extension: RepoExtension) {
        val name = extension.packageName
        if (name in mutableState.value.installing) return
        mutableState.update { it.copy(installing = it.installing + name, failures = it.failures - name) }
        val problem = repos.install(extension.repo, name)
        mutableState.update { state ->
            state.copy(installing = state.installing - name, failures = problem?.let { state.failures + (name to it) } ?: state.failures)
        }
        showListing(refresh = false)
    }

    private var listingRequest = 0
    private var refreshing = false

    private suspend fun showListing(refresh: Boolean) {
        val request = ++listingRequest
        refreshing = refresh || refreshing
        val fresh = refreshing
        val repos = repos.repos()
        mutableState.update { it.copy(repos = repos) }
        val listing = this.repos.available(fresh)
        if (request != listingRequest) return
        refreshing = false
        mutableState.update { it.copy(listing = listing) }
    }

    private fun show(listed: List<InstalledExtension>) = mutableState.update { it.copy(extensions = listed) }

    private suspend fun showSettings(id: String, read: suspend () -> List<ExtensionSourceSettings>) {
        val settings = read()
        mutableState.update { if (it.openId == id) it.copy(settings = settings) else it }
    }
}
