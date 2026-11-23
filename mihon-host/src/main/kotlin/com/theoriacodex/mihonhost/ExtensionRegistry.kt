package com.theoriacodex.mihonhost

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
import com.theoriacodex.domain.series.defaultLanguages
import com.theoriacodex.domain.series.trusts
import com.theoriacodex.sources.http.HostOutageTracker
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
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

/** An extension source's [name] and the [hosts] it is known to call: its site's, and any whose check stopped it. */
data class ExtensionSite(val name: String, val hosts: Set<String>)

/**
 * Decides which extension code runs and which of its sources the app offers.
 *
 * An extension's code is loaded only once the user has turned that extension on ([ExtensionChoiceStore]), and only
 * while it still has the signing certificate it had then; until then it is listed from what the package manager says
 * about it. Loading happens on first use, never at startup, and loads only what that use needs: a lookup of one
 * source ([source]) loads the extension this device last saw offer it ([ExtensionChoices.owners]), and only a source it
 * never saw, or no longer finds there, loads every extension that is on, as the whole list ([sources]) and the
 * Extensions page do. Extensions load one at a time, each on a thread of its own with [limitMs] to finish: one that
 * hangs or fails is left out and says why in the list. Loaded code cannot be unloaded, so turning an extension off stops its sources being
 * offered and its images being fetched, and its code stays idle in memory until the process ends.
 *
 * Once loaded, the sources on offer change only when the device is listed again: on a choice made here, when the
 * Extensions page asks, and on [recheck], which the host calls whenever the extensions on the device may have changed.
 * Nothing is asked of the device per source lookup or image.
 *
 * @param packages the extensions on the device, read without running them
 * @param load creates an extension's sources, running its code
 * @param settings the settings of a source that has some
 * @param deviceLanguages the user's languages as language tags, most preferred first
 */
class ExtensionRegistry(
    private val packages: () -> List<ExtensionPackage>,
    private val load: (ExtensionPackage) -> List<Source>,
    private val choices: ExtensionChoiceStore,
    private val images: ExtensionImages,
    private val settings: (ConfigurableSource) -> SourceSettings,
    private val deviceLanguages: () -> List<String>,
    private val limitMs: Long = LIMIT_MS,
    private val log: (String) -> Unit = {},
) : SeriesExtensions {
    private class LoadedSource(val source: Source, val series: MihonSeriesSource, val name: String, val language: String)

    /** What loading one extension gave: its sources, or why there are none. */
    private class Loaded(val sources: List<LoadedSource>, val problem: String? = null)

    private val mutex = Mutex()

    /** Extension code that may hang runs here, so the caller can stop waiting for it. */
    private val threads = Executors.newCachedThreadPool { task -> Thread(task, "extension").apply { isDaemon = true } }

    /**
     * The extensions on the device that were loaded in this process. The key is the whole package as it was listed,
     * signer and file stamp included, so a file replaced in place is another extension that loads anew: code is never
     * run under a signer or a label it was not loaded with. Guarded by [mutex].
     */
    private val loaded = HashMap<ExtensionPackage, Loaded>()

    /** Per extension id, its sources that are on, as of the last listing. Written under [mutex]; [sites] reads it without. */
    @Volatile
    private var active: Map<String, List<LoadedSource>> = emptyMap()

    private val challengedHosts = MutableStateFlow<Map<SourceKey, Set<String>>>(emptyMap())

    /** Changes when a source is found to call another host, which [sites] then includes; held for this process. */
    val siteChanges: StateFlow<*> = challengedHosts

    /**
     * The sources that are on, with their names and the hosts they call (an HTTP source's base address, and any host
     * whose challenge stopped one of its calls), so a challenged host can be traced to the sources it stops. Loads
     * nothing: a source that never loaded made no request.
     */
    fun sites(): Map<SourceKey, ExtensionSite> {
        val challenged = challengedHosts.value
        return active.values.flatten().mapNotNull { loaded ->
            // An extension's address can depend on its settings, and its code can fail.
            val base = (loaded.source as? HttpSource)?.let { runCatching { it.baseUrl }.getOrNull() }?.let(HostOutageTracker::hostKey)
            val hosts = setOfNotNull(base) + challenged[loaded.series.key].orEmpty()
            if (hosts.isEmpty()) null else loaded.series.key to ExtensionSite(loaded.name, hosts)
        }.toMap()
    }

    /**
     * The sources on offer, with the [packages] they were listed from; [complete] when every extension that is on was
     * loaded for them, rather than only the ones lookups needed.
     */
    private class Offer(val sources: List<SeriesSource>, val packages: List<ExtensionPackage>, val complete: Boolean) {
        fun find(key: SourceKey): SeriesSource? = sources.firstOrNull { it.key == key }
    }

    /** Null until first asked for. Replaced once a listing has finished: a listing that was cancelled leaves it as it was. */
    @Volatile
    private var offer: Offer? = null

    private val mutableRevision = MutableStateFlow(0)
    override val revision: StateFlow<Int> = mutableRevision

    /** The sources of the extensions and languages that are on. The first call loads them all; later calls read what is on offer. */
    suspend fun sources(): List<SeriesSource> = offer?.takeIf { it.complete }?.sources ?: locked {
        loadEverything()
        offer?.sources.orEmpty()
    }

    /**
     * The source [key] names, when its extension and language are on; null otherwise. Loads the extension this device
     * last saw offer it, or every extension that is on when it saw none, that one is no longer on the device (renamed
     * extensions keep their source ids), or it loaded without it. An owner on the device that is off, or offers the
     * source only in a language that is off, answers null at once: loading the rest would not find it.
     */
    suspend fun source(key: SourceKey): SeriesSource? {
        offer?.let { current ->
            current.find(key)?.let { return it }
            // Every extension that is on already loaded: the source is not on.
            if (current.complete) return null
        }
        // A chapter's pages all ask at once on a cold start: only the first to get here lists the device.
        return locked {
            offer?.find(key)?.let { return@locked it }
            // Even when reading the choices fails: package changes then retry, as for any other first listing.
            listed = true
            val owner = choices.choices().owners[key.id]
            if (owner != null) {
                // The device as last listed: the package watcher lists it again whenever it changes.
                if (wanted.add(owner) || offer == null) refresh(offer?.packages)
                offer?.find(key)?.let { return@locked it }
                val present = offer?.packages.orEmpty().any { it.identity == owner }
                val code = loaded.entries.firstOrNull { it.key.identity == owner }?.value
                if (present && (code == null || code.sources.any { it.series.key == key })) return@locked null
            }
            loadEverything()
            offer?.find(key)
        }
    }

    /** Whether a listing was ever started, even one that failed. Written under [mutex]. */
    private var listed = false

    /** Whether every extension that is on is to load, as the list and every Extensions page call need. Guarded by [mutex]. */
    private var everything = false

    /** Until [everything], the extensions to load (by [ExtensionPackage.identity]): the ones lookups needed. Guarded by [mutex]. */
    private val wanted = HashSet<String>()

    /**
     * Lists the device again once a listing has been tried, so an extension installed, updated, removed, hidden or
     * unhidden since is picked up, and a first load that failed is tried again; only the extensions that changed load.
     * Moves [revision] when something changed. Before any listing, does nothing: nothing loads until first asked for.
     */
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
            // Only what the user was shown is trusted; a file replaced since then is listed again to be asked about.
            extension.signer == signer -> {
                choices.update { it.copy(trusted = it.trusted + (extension.identity to signer)) }
                // Turning one on again is the way to retry it when it failed.
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
        // Looked up among the sources that are on now: an edit queued before its extension was turned off does nothing.
        val sources = active[id].orEmpty()
        val source = sources.firstOrNull { it.series.key.id == sourceId }
        val configurable = source?.source as? ConfigurableSource
        if (configurable != null) limited("${source.name} settings") { settings(configurable).change(setting) }
        sources.mapNotNull(::settingsOf)
    }

    /**
     * Runs [block] alone and off the caller's thread. The revision moves when the extensions on the device changed, a
     * package installed or removed since the last listing, also one that is off, which the Extensions page lists; or
     * when a [complete][Offer.complete] offer's sources changed, whatever the cause. Sources added to an offer that was
     * not complete, as lookups load what they need, move nothing: only complete lists are handed out. It stays put
     * when a listing finds the same extensions, as after any other app updates.
     */
    private suspend fun <T> locked(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            val before = offer
            block().also {
                val moved = before?.packages != offer?.packages || before?.complete == true && before.sources != offer?.sources
                if (moved) mutableRevision.update { it + 1 }
            }
        }
    }

    /** Loads every extension that is on, listing the device unless that was done already. */
    private suspend fun loadEverything() {
        if (everything && offer?.complete == true) return
        everything = true
        refresh()
    }

    /**
     * Lists every extension, loading those that are on, not loaded yet, and to load ([everything] or [wanted]), and
     * replaces what is on offer. Records which extension offers each source it loaded, for later lookups. No settings
     * are read here: this runs for the first source lookup and image request, which must not wait on every source's
     * settings screen.
     */
    private suspend fun refresh(found: List<ExtensionPackage>? = null): List<InstalledExtension> {
        listed = true
        val onDevice = found ?: packages()
        val choices = choices.choices()
        val device = deviceLanguages()
        val taken = HashSet<SourceKey>()
        val on = LinkedHashMap<String, List<LoadedSource>>()
        val learned = HashMap<String, String>()
        // What is no longer on the device as it was loaded is never offered again.
        loaded.keys.retainAll(onDevice.toSet())
        val listed = onDevice.map { extension ->
            val trusted = choices.trusts(extension.identity, extension.signer)
            val listing = InstalledExtension(
                id = extension.id,
                // A file is told apart from the installed package of the same extension.
                name = if (extension.installed) extension.name else "${extension.name} (file)",
                versionName = extension.versionName,
                signer = extension.signer,
                on = trusted,
                problem = extension.unsupported ?: NOT_SIGNED.takeIf { extension.signer == null },
            )
            if (!trusted || extension.unsupported != null) return@map listing
            val code = loaded[extension] ?: if (everything || extension.identity in wanted) {
                load(extension).also { code ->
                    loaded[extension] = code
                    // The copy listed first, the installed one, is the one the list offers when a file duplicates it.
                    code.sources.forEach { learned.putIfAbsent(it.series.key.id, extension.identity) }
                }
            } else {
                return@map listing
            }
            val languages = code.sources.map { it.language }.distinct()
            val chosen = choices.languages[extension.packageName] ?: defaultLanguages(languages, device)
            // The same extension can be on the device twice, installed and as a file; its sources are offered once.
            on[listing.id] = code.sources.filter { it.language in chosen && taken.add(it.series.key) }
            listing.copy(problem = code.problem, languages = languages.map { ExtensionLanguage(it, it in chosen) })
        }
        // Only a hint for the next start: failing to keep it leaves that start loading every extension for a lookup. Not
        // cancelled midway, so what loaded is always published with it.
        if (learned.isNotEmpty()) {
            withContext(NonCancellable) {
                runCatchingPreservingCancellation { this@ExtensionRegistry.choices.update { it.copy(owners = it.owners + learned) } }
                    .onFailure { Diagnostics.report(LOG_AREA, "Which extension offers each source could not be kept", it) }
            }
        }
        active = on
        val sources = on.values.flatten()
        // One complete table: only sources of an extension that finished loading, in a language that is on, fetch images.
        images.publish(sources.map { it.source })
        val series = sources.map { it.series }
        // The same list object while nothing changed, so a reader can tell.
        offer = Offer(offer?.sources?.takeIf { it == series } ?: series, onDevice, complete = everything)
        return listed
    }

    /** [source] as a series source, whose challenged hosts join its [sites] entry. */
    private fun series(source: Source): MihonSeriesSource {
        val key = MihonSeriesSource.sourceKey(source)
        return MihonSeriesSource(source, images) { host ->
            challengedHosts.update { hosts -> hosts + (key to hosts[key].orEmpty() + host) }
        }
    }

    private fun load(extension: ExtensionPackage): Loaded = limited(extension.name) {
        // A source's name, language and id are its code too, so they are read here, within the limit.
        load.invoke(extension).map { source -> LoadedSource(source, series(source), source.name, source.lang) }
    }.fold(
        onSuccess = { sources ->
            log("Loaded ${extension.name} ${extension.versionName}: ${sources.size} sources, signer ${extension.signer}")
            Loaded(sources, NO_SOURCES.takeIf { sources.isEmpty() })
        },
        onFailure = { error -> Loaded(emptyList(), error.message ?: error.toString()) },
    )

    /** Builds the source's settings screen, which runs its code, within the limit. */
    private fun settingsOf(source: LoadedSource): ExtensionSourceSettings? {
        val configurable = source.source as? ConfigurableSource ?: return null
        val list = limited("${source.name} settings") { settings(configurable).list() }.getOrNull().orEmpty()
        return list.takeIf { it.isNotEmpty() }?.let { ExtensionSourceSettings(source.series.key.id, source.name, source.language, it) }
    }

    /**
     * Runs extension code on a thread of its own and waits up to [limitMs] for it. Whatever it throws, a missing host
     * class included, and running out of time become a failure that names [what]; a thread that hangs is abandoned.
     */
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
        // The failure's message is the extension's and can hold an address or a title, which only the redacted log may keep.
        Diagnostics.report(LOG_AREA, "Skipped $what", failure)
        return Result.failure(failure)
    }

    private companion object {
        const val LIMIT_MS = 15_000L
        const val MILLIS_PER_SECOND = 1_000L
        const val NOT_SIGNED = "Not signed"
        const val NO_SOURCES = "No sources"
        const val LOG_AREA = "extensions"
    }
}
