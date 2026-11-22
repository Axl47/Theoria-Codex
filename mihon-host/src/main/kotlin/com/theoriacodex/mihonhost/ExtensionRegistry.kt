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
import java.io.File
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
 * An extension's code is loaded only once the user has turned its signing certificate on ([ExtensionChoiceStore]);
 * until then it is listed from what the package manager says about it. Loading happens on first use, never at startup,
 * one extension at a time, each on a thread of its own with [limitMs] to finish: one that hangs or fails is left out
 * and says why in the list. Loaded code cannot be unloaded, so turning an extension off stops its sources being
 * offered and its images being fetched, and its code stays idle in memory until the process ends.
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

    /** Every extension loaded in this process, by its APK; an update is another APK. Guarded by [mutex]. */
    private val loaded = HashMap<File, Loaded>()

    /** The sources on offer; null until first asked for. */
    @Volatile
    private var offered: List<SeriesSource>? = null

    private val mutableRevision = MutableStateFlow(0)
    override val revision: StateFlow<Int> = mutableRevision

    /** The sources of the extensions and languages that are on. The first call loads them; later calls are immediate. */
    suspend fun sources(): List<SeriesSource> = offered ?: withContext(Dispatchers.IO) {
        mutex.withLock { offered ?: refresh().let { offered.orEmpty() } }
    }

    override suspend fun installed(): List<InstalledExtension> = locked { refresh() }

    override suspend fun setOn(id: String, on: Boolean) {
        changing {
            val signer = extension(id)?.signer ?: return@changing
            choices.update { it.copy(trustedSigners = if (on) it.trustedSigners + signer else it.trustedSigners - signer) }
            // Turning one on again is the way to retry one that failed.
            if (on) loaded.entries.removeAll { it.value.problem != null }
        }
    }

    override suspend fun setLanguage(id: String, language: String, on: Boolean) {
        changing { listed ->
            val extension = extension(id) ?: return@changing
            val current = listed.firstOrNull { it.id == id }?.languages.orEmpty().filter(ExtensionLanguage::on).mapTo(LinkedHashSet(), ExtensionLanguage::code)
            choices.update { it.copy(languages = it.languages + (extension.packageName to if (on) current + language else current - language)) }
        }
    }

    override suspend fun change(id: String, sourceId: String, setting: ExtensionSetting) {
        changing {
            val source = loaded[File(id)]?.sources?.firstOrNull { it.series.key.id == sourceId }?.source as? ConfigurableSource ?: return@changing
            limited("${source.name} settings") { settings(source).change(setting) }
        }
    }

    /** Lists the extensions, lets [change] act on that list, then lists them again as the result. */
    private suspend fun changing(change: suspend (List<InstalledExtension>) -> Unit): List<InstalledExtension> = locked {
        change(refresh())
        refresh()
    }

    /**
     * Runs [block] alone and off the caller's thread. The revision moves when the sources on offer did, whatever the
     * cause: a choice made here, or a package installed or removed since the last listing.
     */
    private suspend fun locked(block: suspend () -> List<InstalledExtension>): List<InstalledExtension> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val before = offered
            block().also { if (before != offered) mutableRevision.update { it + 1 } }
        }
    }

    private fun extension(id: String): ExtensionPackage? = packages().firstOrNull { it.apk.path == id }

    /** Lists every extension, loading those that are on and not loaded yet, and replaces [offered]. */
    private suspend fun refresh(): List<InstalledExtension> {
        val choices = choices.choices()
        val device = deviceLanguages()
        val taken = HashSet<SourceKey>()
        val on = mutableListOf<LoadedSource>()
        val listed = packages().map { extension ->
            val trusted = choices.trusts(extension.signer)
            val listing = InstalledExtension(
                id = extension.apk.path,
                // A file is told apart from the installed package of the same extension.
                name = if (extension.installed) extension.name else "${extension.name} (file)",
                versionName = extension.versionName,
                signer = extension.signer,
                on = trusted,
                problem = extension.unsupported ?: NOT_SIGNED.takeIf { extension.signer == null },
            )
            if (!trusted || extension.unsupported != null) return@map listing
            val code = loaded.getOrPut(extension.apk) { load(extension) }
            val languages = code.sources.map { it.language }.distinct()
            val chosen = choices.languages[extension.packageName] ?: defaultLanguages(languages, device)
            // The same extension can be on the device twice, installed and as a file; its sources are offered once.
            val sources = code.sources.filter { it.language in chosen && taken.add(it.series.key) }
            on += sources
            listing.copy(
                problem = code.problem,
                languages = languages.map { ExtensionLanguage(it, it in chosen) },
                settings = sources.mapNotNull(::settingsOf),
            )
        }
        images.only(on.map { it.source })
        val series = on.map { it.series }
        // The same list object while nothing changed, so a reader can tell.
        if (series != offered) offered = series
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
