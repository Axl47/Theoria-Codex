package com.theoriacodex.extensionhost

import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.series.ExtensionChoiceStore
import com.theoriacodex.domain.series.ExtensionRepo
import com.theoriacodex.domain.series.ExtensionRepos
import com.theoriacodex.domain.series.NewExtensionRepo
import com.theoriacodex.domain.series.RepoExtension
import com.theoriacodex.domain.series.RepoListing
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.awaitSuccess
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The user's extension repos and installing from them. Repos are kept with the user's other choices about extensions
 * ([ExtensionChoiceStore]), on this device only, each with the signing key it named when it was added: an APK is
 * installed only when it is the package the index named and is signed by that key, and a repo that names another key
 * later is skipped until it is added again.
 *
 * Installing writes the APK into [folder] as `<package>.apk`, where [ExtensionPackages] lists it as a private copy that
 * stands in for an installed package of the same name. Only this class writes there, after those checks, so release
 * builds load code from it. An extension installed here that was not on the device before is turned on, being the
 * one the user chose from a repo they added; an update keeps its choice. Android sends no broadcast for this folder,
 * so [onChanged] runs after each change, once the extension's choice is stored.
 *
 * @param packages the extensions on the device, as [ExtensionPackages.list] reads them
 * @param read the extension in a downloaded APK, as [ExtensionPackages.read] reads it
 */
class ExtensionCatalog(
    private val choices: ExtensionChoiceStore,
    private val client: OkHttpClient,
    private val kinds: List<ExtensionKind>,
    private val packages: () -> List<ExtensionPackage>,
    private val read: (File) -> ExtensionPackage?,
    private val folder: File,
    private val onChanged: suspend () -> Unit = {},
) : ExtensionRepos {
    /** Per repo address, its index as last read in this process. */
    private val indexes = ConcurrentHashMap<String, RepoIndex>()

    /** One change to [folder] at a time. */
    private val installing = Mutex()

    override suspend fun repos(): List<ExtensionRepo> = choices.choices().repos

    override suspend fun add(repos: List<NewExtensionRepo>): Map<String, String> = withContext(Dispatchers.IO) {
        val problems = LinkedHashMap<String, String>()
        for (new in repos) {
            val url = RepoReader.storedAddress(new.url)
            if (repos().any { it.url == url }) continue
            runCatchingPreservingCancellation { addOne(url, new.signingKey) }.onFailure { problems[new.url] = describe(it) }
        }
        problems
    }

    /** Reads the repo at [url] and keeps it with the key it names, which must be [knownKey] when a backup knew one. */
    private suspend fun addOne(url: String, knownKey: String?) {
        if (!url.startsWith("https://")) throw RepoProblem("Not an https address")
        val index = RepoReader.read(url, ::get)
        if (!index.signingKey.matches(FINGERPRINT)) throw RepoProblem("Names no signing key")
        if (knownKey != null && !knownKey.equals(index.signingKey, ignoreCase = true)) throw RepoProblem("Now signed with another key")
        val name = index.name.ifBlank { url }
        var sameKey: ExtensionRepo? = null
        choices.update { current ->
            sameKey = current.repos.firstOrNull { it.url == url || it.signingKey == index.signingKey }
            if (sameKey != null) current else current.copy(repos = current.repos + ExtensionRepo(url, name, index.signingKey))
        }
        // Two addresses of one repo, such as its folder and its newer index, are the same repo. Another repo signed with
        // the same key would list the same packages, which one device can hold only once.
        sameKey?.let { if (it.url != url && it.name != name) throw RepoProblem("Signed with the same key as ${it.name}") }
        indexes[url] = index
    }

    override suspend fun remove(url: String) {
        choices.update { current -> current.copy(repos = current.repos.filterNot { it.url == url }) }
        indexes -= url
    }

    override suspend fun available(refresh: Boolean): RepoListing = withContext(Dispatchers.IO) {
        val repos = repos()
        val read = coroutineScope {
            repos.map { repo -> async { repo to runCatchingPreservingCancellation { index(repo, refresh) } } }.awaitAll()
        }
        val onDevice = packages().filter { it.origin != ExtensionOrigin.FILE }.associateBy(ExtensionPackage::packageName)
        val extensions = read.flatMap { (repo, index) -> index.getOrNull()?.let { offered(repo, it, onDevice) }.orEmpty() }
        RepoListing(extensions, read.mapNotNull { (repo, index) -> index.exceptionOrNull()?.let { repo.url to describe(it) } }.toMap())
    }

    override suspend fun install(repo: String, packageName: String): String? = installing.withLock {
        withContext(Dispatchers.IO) {
            runCatchingPreservingCancellation { installFrom(repo, packageName) }.getOrElse(::describe)
        }
    }

    override suspend fun uninstall(id: String) = installing.withLock {
        withContext(Dispatchers.IO) {
            val extension = packages().firstOrNull { it.id == id && it.origin == ExtensionOrigin.PRIVATE } ?: return@withContext
            extension.apk.delete()
            choices.update { it.copy(trusted = it.trusted - extension.identity) }
            onChanged()
        }
    }

    private suspend fun installFrom(url: String, packageName: String): String? {
        // It names the file the APK is written to.
        if (!packageName.matches(PACKAGE_NAME)) return "Not a package name"
        val repo = repos().firstOrNull { it.url == url } ?: return "The repo was removed"
        val entry = index(repo, refresh = false).extensions.firstOrNull { it.packageName == packageName } ?: return "No longer in the repo"
        val before = packages().firstOrNull { it.origin != ExtensionOrigin.FILE && it.packageName == packageName }
        if (before != null && before.signer != repo.signingKey) return "The copy on this device is signed with another key"
        folder.mkdirs()
        // Named so nothing lists it until it is checked and moved into place.
        val download = File(folder, "$packageName.apk.new")
        try {
            download(entry.apkUrl, download)
            val apk = read(download)
            val problem = when {
                apk == null || apk.packageName != packageName -> "The download is not ${entry.name}"
                apk.signer != repo.signingKey -> "Not signed with the repo's key"
                before != null && apk.versionCode < before.versionCode -> "Older than the copy on this device"
                else -> apk.unsupported
            }
            if (problem != null) return problem
            // Replaced in one step: code already loaded from the old file runs on from its own locked copy.
            Files.move(download.toPath(), File(folder, "$packageName.apk").toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            if (before == null) choices.update { it.copy(trusted = it.trusted + (packageName to repo.signingKey)) }
            onChanged()
            return null
        } finally {
            download.delete()
        }
    }

    /** [repo]'s index, read again when [refresh] or not yet read; refused when the repo names another key than it did. */
    private suspend fun index(repo: ExtensionRepo, refresh: Boolean): RepoIndex {
        val index = indexes[repo.url]?.takeUnless { refresh } ?: RepoReader.read(repo.url, ::get).also { indexes[repo.url] = it }
        if (index.signingKey != repo.signingKey) throw RepoProblem("Now signed with another key; remove it and add it again to trust that key")
        return index
    }

    /** What [index] offers that this host runs, with how each stands against the packages [onDevice]. */
    private fun offered(repo: ExtensionRepo, index: RepoIndex, onDevice: Map<String, ExtensionPackage>) = index.extensions
        .filter { entry -> !entry.torrent && kinds.any { it.seriesKind == entry.kind && it.runs(entry.libVersion) } }
        .map { entry ->
            val installed = onDevice[entry.packageName]
            val status = when {
                installed == null -> RepoExtension.Status.AVAILABLE
                installed.signer != repo.signingKey -> RepoExtension.Status.OTHER_KEY
                installed.versionCode < entry.versionCode -> RepoExtension.Status.UPDATE
                else -> RepoExtension.Status.INSTALLED
            }
            RepoExtension(repo.url, entry.packageName, entry.name, entry.languages, entry.versionName, entry.kind, status)
        }

    private suspend fun get(url: String): ByteArray = client.newCall(request(url)).awaitSuccess().use { it.body.bytes() }

    /** Writes [url]'s body to [file], stopping once it passes [MAX_APK_BYTES]. */
    private suspend fun download(url: String, file: File) {
        client.newCall(request(url)).awaitSuccess().use { response ->
            if (response.body.contentLength() > MAX_APK_BYTES) throw RepoProblem("Too large")
            response.body.byteStream().use { input -> file.outputStream().use { output -> copyAtMost(input, output) } }
        }
    }

    private fun copyAtMost(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var copied = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return
            copied += read
            if (copied > MAX_APK_BYTES) throw RepoProblem("Too large")
            output.write(buffer, 0, read)
        }
    }

    private fun request(url: String): Request = try {
        Request.Builder().url(url).build()
    } catch (_: IllegalArgumentException) {
        throw RepoProblem("Not a web address")
    }

    /** A failure already worded for the Extensions page. */
    private class RepoProblem(message: String) : Exception(message)

    private fun describe(error: Throwable): String = when (error) {
        is RepoProblem -> error.message.orEmpty()
        is HttpException -> "Answered ${error.code}"
        is SerializationException, is IllegalArgumentException -> "Not an extension repo"
        is IOException -> "Couldn't reach it"
        else -> error.message ?: error.javaClass.simpleName
    }

    private companion object {
        val FINGERPRINT = Regex("[0-9a-f]{64}")
        val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)*")
        const val MAX_APK_BYTES = 64L * 1024 * 1024
    }
}
