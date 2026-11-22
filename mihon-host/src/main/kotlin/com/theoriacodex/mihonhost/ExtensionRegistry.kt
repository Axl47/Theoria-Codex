package com.theoriacodex.mihonhost

import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.ExtensionChoiceStore
import com.theoriacodex.domain.series.ExtensionLanguage
import com.theoriacodex.domain.series.ExtensionSetting
import com.theoriacodex.domain.series.ExtensionSourceSettings
import com.theoriacodex.domain.series.InstalledExtension
import com.theoriacodex.domain.series.SeriesExtensions
import com.theoriacodex.domain.series.SeriesSource
import com.theoriacodex.domain.series.defaultLanguages
import com.theoriacodex.domain.series.trusts
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.Source
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Decides which extension code runs and which of its sources the app offers.
 *
 * An extension's code is loaded only once the user has turned that extension on ([ExtensionChoiceStore]), and only
 * while it still has the signing certificate it had then; until then it is listed from what the package manager says
 * about it. Loading happens on first use, never at startup,
 * one extension at a time, each on a thread of its own with [limitMs] to finish: one that hangs or fails is left out
 * and says why in the list. Loaded code cannot be unloaded, so turning an extension off stops its sources being
 * offered and its images being fetched, and its code stays idle in memory until the process ends.
 *
 * @param packages the extensions on the device, read without running them
 * @param stamp a cheap value that differs whenever [packages] would answer differently
 * @param load creates an extension's sources, running its code
 * @param settings the settings of a source that has some
 * @param deviceLanguages the user's languages as language tags, most preferred first
 */
class ExtensionRegistry(
    private val packages: () -> List<ExtensionPackage>,
    private val stamp: () -> Any = packages,
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

    /** Per extension id, its sources that are on, as of the last listing. Guarded by [mutex]. */
    private var active: Map<String, List<LoadedSource>> = emptyMap()

    /** The sources on offer with [stamp] as it was read before the listing they came from. */
    private class Offer(val sources: List<SeriesSource>, val stamp: Any)

    /** What a listing found on the device, with [stamp] read first, so a change during the listing is seen by the next check. */
    private class OnDevice(val stamp: Any, val packages: List<ExtensionPackage>)

    /**
     * Null until first asked for. Sources and stamp are replaced together, once a listing has finished: a reader never
     * pairs a new stamp with the sources of the listing before it, and a listing that was cancelled leaves both as they were.
     */
    @Volatile
    private var offer: Offer? = null

    private val mutableRevision = MutableStateFlow(0)
    override val revision: StateFlow<Int> = mutableRevision

    /**
     * The sources of the extensions and languages that are on. The first call loads them. Every later call checks
     * [stamp], so an extension installed, updated or removed since is picked up without a restart: while nothing
     * changed that check is all a call costs, and when something did only the extensions that changed load.
     */
    suspend fun sources(): List<SeriesSource> = withContext(Dispatchers.IO) {
        offer?.takeIf { stamp() == it.stamp }?.sources ?: locked { refresh().let { offer?.sources.orEmpty() } }
    }

    /** Looks for a change on the device once the extensions have loaded, moving [revision] when there is one; before that, does nothing. */
    suspend fun recheck() {
        if (offer != null) sources()
    }

    override suspend fun installed(): List<InstalledExtension> = locked { refresh() }

    override suspend fun setOn(id: String, signer: String, on: Boolean): List<InstalledExtension> = locked {
        val onDevice = onDevice()
        val extension = onDevice.packages.firstOrNull { it.id == id }
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
        val onDevice = onDevice()
        val extension = onDevice.packages.firstOrNull { it.id == id }
        val languages = extension?.let { loaded[it] }?.sources?.map { it.language }
        if (extension != null && languages != null) {
            val current = choices.choices().languages[extension.packageName] ?: defaultLanguages(languages.distinct(), deviceLanguages())
            choices.update { it.copy(languages = it.languages + (extension.packageName to if (on) current + language else current - language)) }
        }
        refresh(onDevice)
    }

    override suspend fun settings(id: String): List<ExtensionSourceSettings> = locked {
        if (offer == null) refresh()
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
     * Runs [block] alone and off the caller's thread. The revision moves when the sources on offer did, whatever the
     * cause: a choice made here, or a package installed or removed since the last listing.
     */
    private suspend fun <T> locked(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            val before = offer?.sources
            block().also { if (before != offer?.sources) mutableRevision.update { it + 1 } }
        }
    }

    private fun onDevice() = OnDevice(stamp(), packages())

    /**
     * Lists every extension, loading those that are on and not loaded yet, and replaces what is on offer. No settings
     * are read here: this runs for the first source lookup and image request, which must not wait on every source's
     * settings screen.
     */
    private suspend fun refresh(found: OnDevice = onDevice()): List<InstalledExtension> {
        val onDevice = found.packages
        val choices = choices.choices()
        val device = deviceLanguages()
        val taken = HashSet<SourceKey>()
        val on = LinkedHashMap<String, List<LoadedSource>>()
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
            val code = loaded.getOrPut(extension) { load(extension) }
            val languages = code.sources.map { it.language }.distinct()
            val chosen = choices.languages[extension.packageName] ?: defaultLanguages(languages, device)
            // The same extension can be on the device twice, installed and as a file; its sources are offered once.
            on[listing.id] = code.sources.filter { it.language in chosen && taken.add(it.series.key) }
            listing.copy(problem = code.problem, languages = languages.map { ExtensionLanguage(it, it in chosen) })
        }
        active = on
        val sources = on.values.flatten()
        // One complete table: only sources of an extension that finished loading, in a language that is on, fetch images.
        images.publish(sources.map { it.source })
        val series = sources.map { it.series }
        // The same list object while nothing changed, so a reader can tell.
        offer = Offer(offer?.sources?.takeIf { it == series } ?: series, found.stamp)
        return listed
    }

    private fun load(extension: ExtensionPackage): Loaded = limited(extension.name) {
        // A source's name, language and id are its code too, so they are read here, within the limit.
        load.invoke(extension).map { source -> LoadedSource(source, MihonSeriesSource(source, images, log), source.name, source.lang) }
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
        log("Skipped $what: $failure")
        return Result.failure(failure)
    }

    private companion object {
        const val LIMIT_MS = 15_000L
        const val MILLIS_PER_SECOND = 1_000L
        const val NOT_SIGNED = "Not signed"
        const val NO_SOURCES = "No sources"
    }
}
