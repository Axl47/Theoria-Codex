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

/** The pages of the Extensions area besides the list and one extension's page. */
enum class ExtensionsSubpage { REPOS, BROWSE }

/** The Add repo question: [busy] while the repo is read, [problem] why the last address was refused. */
data class AddRepo(val busy: Boolean = false, val problem: String? = null)

/**
 * The Extensions page. [extensions] is every extension on the device, null until they are first listed. [openId] is
 * the extension whose own page shows and [settings] that extension's settings, null while they load. [asking] is the
 * extension the user is being asked to turn on, with the signer the question shows. [repos] and [listing] are the
 * user's repos and what they offer, null until read; [installing] the packages being installed and [failures] why an
 * install failed, by package.
 */
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
    /** Extensions the repos have a newer version of. */
    val updates: List<RepoExtension> get() = listing?.extensions.orEmpty().filter { it.status == RepoExtension.Status.UPDATE }
}

class ExtensionsViewModel(private val extensions: SeriesExtensions, private val repos: ExtensionRepos) : ViewModel() {
    private val mutableState = MutableStateFlow(ExtensionsState())
    val state: StateFlow<ExtensionsState> = mutableState

    init {
        // Lists the extensions now and again whenever one is installed, removed or switched while the page shows; what
        // the repos offer is compared with the device again each time.
        viewModelScope.launch {
            extensions.revision.collect {
                show(extensions.installed())
                if (mutableState.value.listing != null) showListing(refresh = false)
            }
        }
        // Opening the page checks the repos for updates.
        viewModelScope.launch { showListing(refresh = true) }
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

    /** Shows [subpage], or with null goes back to the list. */
    fun openSubpage(subpage: ExtensionsSubpage?) = mutableState.update { it.copy(subpage = subpage) }

    /** Opens the Add repo question; null withdraws it. */
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

    /** Updates every extension the repos have a newer version of, one after another. */
    fun updateAll() {
        val updates = mutableState.value.updates
        viewModelScope.launch { updates.forEach { installOne(it) } }
    }

    /** Removes [extension], which this app installed, and goes back to the list. */
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

    private suspend fun showListing(refresh: Boolean) {
        val repos = repos.repos()
        mutableState.update { it.copy(repos = repos) }
        val listing = this.repos.available(refresh)
        mutableState.update { it.copy(listing = listing) }
    }

    private fun show(listed: List<InstalledExtension>) = mutableState.update { it.copy(extensions = listed) }

    private suspend fun showSettings(id: String, read: suspend () -> List<ExtensionSourceSettings>) {
        val settings = read()
        mutableState.update { if (it.openId == id) it.copy(settings = settings) else it }
    }
}
