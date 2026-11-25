package com.theoriacodex.extensionhost

import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.diagnostics.Diagnostics
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.ExtensionChoiceStore
import com.theoriacodex.domain.series.ExtensionChoices
import com.theoriacodex.domain.series.ExtensionLanguage
import com.theoriacodex.domain.series.ExtensionSetting
import com.theoriacodex.domain.series.ExtensionSourceSettings
import com.theoriacodex.domain.series.InstalledExtension
import com.theoriacodex.domain.series.SeriesExtensions
import com.theoriacodex.domain.series.SeriesSource
import com.theoriacodex.domain.series.SourceAvailability
import com.theoriacodex.domain.series.SourceOff
import com.theoriacodex.domain.series.defaultLanguages
import com.theoriacodex.domain.series.trusts
import com.theoriacodex.sources.http.HostOutageTracker
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class ExtensionSite(val name: String, val hosts: Set<String>)

class ExtensionRegistry(
    private val packages: () -> List<ExtensionPackage>,
    private val load: (ExtensionPackage, HostServices) -> List<HostedSource>,
    private val choices: ExtensionChoiceStore,
    private val images: ExtensionImages,
    private val settings: (SettingsHook) -> SourceSettings,
    private val deviceLanguages: () -> List<String>,
    private val limitMs: Long = LIMIT_MS,
    private val log: (String) -> Unit = {},
) : SeriesExtensions {
    private class Loaded(val sources: List<HostedSource>, val problem: String? = null)

    private val mutex = Mutex()

    private val threads = Executors.newCachedThreadPool { task -> Thread(task, "extension").apply { isDaemon = true } }

    private val loaded = HashMap<ExtensionPackage, Loaded>()

    @Volatile
    private var active: Map<String, List<HostedSource>> = emptyMap()

    private val challengedHosts = MutableStateFlow<Map<SourceKey, Set<String>>>(emptyMap())

    private val services = HostServices(images, log) { source, host ->
        challengedHosts.update { hosts -> hosts + (source to hosts[source].orEmpty() + host) }
    }

    val siteChanges: StateFlow<*> = challengedHosts

    fun sites(): Map<SourceKey, ExtensionSite> {
        val challenged = challengedHosts.value
        return active.values.flatten().mapNotNull { source ->
            val site = runCatching { source.siteAddress() }.getOrNull()?.let(HostOutageTracker::hostKey)
            val hosts = setOfNotNull(site) + challenged[source.key].orEmpty()
            if (hosts.isEmpty()) null else source.key to ExtensionSite(source.name, hosts)
        }.toMap()
    }

    private class Offer(val sources: List<SeriesSource>, val packages: List<ExtensionPackage>, val complete: Boolean) {
        fun find(key: SourceKey): SeriesSource? = sources.firstOrNull { it.key == key }
    }

    @Volatile
    private var offer: Offer? = null

    private val mutableRevision = MutableStateFlow(0)
    override val revision: StateFlow<Int> = mutableRevision

    fun loadedSources(): List<SeriesSource> = offer?.sources.orEmpty()

    suspend fun sources(): List<SeriesSource> = offer?.takeIf { it.complete }?.sources ?: locked {
        loadEverything()
        offer?.sources.orEmpty()
    }

    suspend fun availability(): SourceAvailability = locked {
        loadEverything()
        val choices = choices.choices()
        val on = offer?.sources.orEmpty().mapTo(HashSet()) { it.key }
        val off = HashMap<SourceKey, SourceOff>()
        for (extension in offer?.packages.orEmpty()) {
            if (extension.unsupported != null || extension.signer == null) continue
            if (choices.trusts(extension.identity, extension.signer)) {
                loaded[extension]?.sources.orEmpty().filter { it.key !in on }
                    .forEach { off.putIfAbsent(it.key, SourceOff(extension.label, it.language)) }
            } else {
                choices.owners.filterValues { it == extension.identity }.keys.mapNotNull(SourceKey::parse).filter { it !in on }
                    .forEach { off.putIfAbsent(it, SourceOff(extension.label)) }
            }
        }
        SourceAvailability(on, off)
    }

    suspend fun source(key: SourceKey): SeriesSource? {
        offer?.let { current ->
            current.find(key)?.let { return it }
            if (current.complete) return null
        }
        return locked {
            offer?.find(key)?.let { return@locked it }
            listed = true
            val owner = choices.choices().owners[key.id]
            if (owner == null || !ownerSettles(key, owner)) loadEverything()
            offer?.find(key)
        }
    }

    private suspend fun ownerSettles(key: SourceKey, owner: String): Boolean {
        if (wanted.add(owner) || offer == null) refresh(offer?.packages)
        if (offer?.find(key) != null) return true
        if (offer?.packages.orEmpty().none { it.identity == owner }) return false
        val code = loaded.entries.firstOrNull { it.key.identity == owner }?.value
        return code == null || code.sources.any { it.key == key }
    }

    private var listed = false

    private var everything = false

    private val wanted = HashSet<String>()

    suspend fun recheck() {
        locked { if (listed) refresh() }
    }

    override suspend fun installed(): List<InstalledExtension> = locked {
        everything = true
        refresh()
    }

    override suspend fun setOn(id: String, signer: String, on: Boolean): List<InstalledExtension> = locked {
        everything = true
        val onDevice = packages()
        val extension = onDevice.firstOrNull { it.id == id }
        when {
            extension == null -> Unit
            !on -> choices.update { it.copy(trusted = it.trusted - extension.identity) }
            extension.signer == signer -> {
                choices.update { it.copy(trusted = it.trusted + (extension.identity to signer)) }
                if (loaded[extension]?.problem != null) loaded -= extension
            }
        }
        refresh(onDevice)
    }

    override suspend fun setLanguage(id: String, language: String, on: Boolean): List<InstalledExtension> = locked {
        everything = true
        val onDevice = packages()
        val extension = onDevice.firstOrNull { it.id == id }
        val languages = extension?.let { loaded[it] }?.sources?.map { it.language }
        if (extension != null && languages != null) {
            val current = choices.choices().languages[extension.packageName] ?: defaultLanguages(languages.distinct(), deviceLanguages())
            choices.update { it.copy(languages = it.languages + (extension.packageName to if (on) current + language else current - language)) }
        }
        refresh(onDevice)
    }

    override suspend fun settings(id: String): List<ExtensionSourceSettings> = locked {
        loadEverything()
        active[id].orEmpty().mapNotNull(::settingsOf)
    }

    override suspend fun change(id: String, sourceId: String, setting: ExtensionSetting): List<ExtensionSourceSettings> = locked {
        val sources = active[id].orEmpty()
        val source = sources.firstOrNull { it.key.id == sourceId }
        val hook = source?.settings
        if (hook != null) limited("${source.name} settings") { settings(hook).change(setting) }
        sources.mapNotNull(::settingsOf)
    }

    private suspend fun <T> locked(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            val before = offer
            block().also {
                val moved = before?.packages != offer?.packages || before?.complete == true && before.sources != offer?.sources
                if (moved) mutableRevision.update { it + 1 }
            }
        }
    }

    private suspend fun loadEverything() {
        if (everything && offer?.complete == true) return
        everything = true
        refresh()
    }

    private suspend fun refresh(found: List<ExtensionPackage>? = null): List<InstalledExtension> {
        listed = true
        val onDevice = found ?: packages()
        val choices = choices.choices()
        val device = deviceLanguages()
        val taken = HashSet<SourceKey>()
        val on = LinkedHashMap<String, List<HostedSource>>()
        val learned = HashMap<String, String>()
        loaded.keys.retainAll(onDevice.toSet())
        val listed = onDevice.map { extension ->
            val trusted = choices.trusts(extension.identity, extension.signer)
            val listing = InstalledExtension(
                id = extension.id,
                name = extension.label,
                versionName = extension.versionName,
                signer = extension.signer,
                on = trusted,
                problem = extension.unsupported ?: NOT_SIGNED.takeIf { extension.signer == null },
                kind = extension.kind.seriesKind,
                removable = extension.origin == ExtensionOrigin.PRIVATE,
            )
            if (!trusted || extension.unsupported != null) return@map listing
            val code = loaded[extension] ?: if (everything || extension.identity in wanted) {
                load(extension).also { code ->
                    loaded[extension] = code
                    code.sources.forEach { learned.putIfAbsent(it.key.id, extension.identity) }
                }
            } else {
                return@map listing
            }
            val languages = code.sources.map { it.language }.distinct()
            val chosen = choices.languages[extension.packageName] ?: defaultLanguages(languages, device)
            on[listing.id] = code.sources.filter { it.language in chosen && taken.add(it.key) }
            listing.copy(problem = code.problem, languages = languages.map { ExtensionLanguage(it, it in chosen) })
        }
        if (learned.isNotEmpty()) {
            withContext(NonCancellable) {
                runCatchingPreservingCancellation { this@ExtensionRegistry.choices.update { it.copy(owners = it.owners + learned) } }
                    .onFailure { Diagnostics.report(LOG_AREA, "Which extension offers each source could not be kept", it) }
            }
        }
        active = on
        val sources = on.values.flatten()
        images.publish(sources)
        val series = sources.map { it.series }
        offer = Offer(offer?.sources?.takeIf { it == series } ?: series, onDevice, complete = everything)
        return listed
    }

    private fun load(extension: ExtensionPackage): Loaded = limited(extension.name) { load.invoke(extension, services) }.fold(
        onSuccess = { sources ->
            log("Loaded ${extension.name} ${extension.versionName}: ${sources.size} sources, signer ${extension.signer}")
            Loaded(sources, NO_SOURCES.takeIf { sources.isEmpty() })
        },
        onFailure = { error -> Loaded(emptyList(), error.message ?: error.toString()) },
    )

    private fun settingsOf(source: HostedSource): ExtensionSourceSettings? {
        val hook = source.settings ?: return null
        val list = limited("${source.name} settings") { settings(hook).list() }.getOrNull().orEmpty()
        return list.takeIf { it.isNotEmpty() }?.let { ExtensionSourceSettings(source.key.id, source.name, source.language, it) }
    }

    private fun <T> limited(what: String, block: () -> T): Result<T> {
        val task = threads.submit<T> { block() }
        val failure = try {
            return Result.success(task.get(limitMs, TimeUnit.MILLISECONDS))
        } catch (error: ExecutionException) {
            error.cause ?: error
        } catch (_: TimeoutException) {
            task.cancel(true)
            TimeoutException("Took longer than ${limitMs / MILLIS_PER_SECOND} s")
        }
        Diagnostics.report(LOG_AREA, "Skipped $what", failure)
        return Result.failure(failure)
    }

    private val ExtensionPackage.label: String get() = if (origin == ExtensionOrigin.FILE) "$name (file)" else name

    private companion object {
        const val LIMIT_MS = 15_000L
        const val MILLIS_PER_SECOND = 1_000L
        const val NOT_SIGNED = "Not signed"
        const val NO_SOURCES = "No sources"
        const val LOG_AREA = "extensions"
    }
}
