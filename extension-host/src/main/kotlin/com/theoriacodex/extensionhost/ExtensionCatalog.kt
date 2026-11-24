package com.theoriacodex.extensionhost

import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.series.ExtensionChoiceStore
import com.theoriacodex.domain.series.ExtensionChoices
import com.theoriacodex.domain.series.ExtensionRepo
import com.theoriacodex.domain.series.ExtensionRepos
import com.theoriacodex.domain.series.NewExtensionRepo
import com.theoriacodex.domain.series.RepoExtension
import com.theoriacodex.domain.series.RepoListing
import com.theoriacodex.domain.series.storedRepoAddress
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.awaitSuccess
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import okhttp3.OkHttpClient
import okhttp3.Request

class ExtensionCatalog(
    private val choices: ExtensionChoiceStore,
    private val client: OkHttpClient,
    private val kinds: List<ExtensionKind>,
    private val packages: () -> List<ExtensionPackage>,
    private val read: (File) -> ExtensionPackage?,
    private val folder: File,
    private val onChanged: suspend () -> Unit = {},
) : ExtensionRepos {
    private val indexes = ConcurrentHashMap<String, RepoIndex>()

    private val installing = Mutex()

    override suspend fun repos(): List<ExtensionRepo> = choices.choices().repos

    override suspend fun add(repos: List<NewExtensionRepo>): Map<String, String> = withContext(Dispatchers.IO) {
        val problems = LinkedHashMap<String, String>()
        for (new in repos) {
            val url = storedRepoAddress(new.url)
            if (repos().any { it.url == url }) continue
            runCatchingPreservingCancellation { addOne(url, new.signingKey) }.onFailure { problems[new.url] = describe(it) }
        }
        problems
    }

    override suspend fun restore(repos: List<ExtensionRepo>): Map<String, String> {
        val problems = LinkedHashMap<String, String>()
        val accepted = LinkedHashMap<String, ExtensionRepo>()
        for (repo in repos) {
            val url = storedRepoAddress(repo.url)
            val key = repo.signingKey.lowercase()
            when {
                !url.startsWith("https://") -> problems[repo.url] = "Not an https address"
                !key.matches(FINGERPRINT) -> problems[repo.url] = "Names no signing key"
                else -> accepted.putIfAbsent(url, ExtensionRepo(url, repo.name.ifBlank { url }, key))
            }
        }
        choices.update { current ->
            val held = current.repos.mapTo(HashSet()) { it.url }
            current.copy(repos = current.repos + accepted.values.filter { it.url !in held })
        }
        return problems
    }

    private suspend fun addOne(url: String, knownKey: String?) {
        if (!url.startsWith("https://")) throw RepoProblem("Not an https address")
        val index = RepoReader.read(url, ::get)
        if (!index.signingKey.matches(FINGERPRINT)) throw RepoProblem("Names no signing key")
        if (knownKey != null && !knownKey.equals(index.signingKey, ignoreCase = true)) throw RepoProblem("Now signed with another key")
        choices.update { current ->
            if (current.repos.any { it.url == url }) current
            else current.copy(repos = current.repos + ExtensionRepo(url, index.name.ifBlank { url }, index.signingKey))
        }
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
        val extensions = read.flatMap { (repo, index) -> index.getOrNull()?.let { offered(repo, it, onDevice) }.orEmpty() }.distinctBy(RepoExtension::packageName)
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
            if (!extension.apk.delete()) return@withContext
            withContext(NonCancellable) { commit { it.copy(trusted = it.trusted - extension.identity) } }
        }
    }

    private suspend fun installFrom(url: String, packageName: String): String? {
        if (!packageName.matches(PACKAGE_NAME)) return "Not a package name"
        val repo = repos().firstOrNull { it.url == url } ?: return "The repo was removed"
        val entry = index(repo, refresh = false).extensions.firstOrNull { it.packageName == packageName } ?: return "No longer in the repo"
        val before = packages().firstOrNull { it.origin != ExtensionOrigin.FILE && it.packageName == packageName }
        if (before != null && before.signer != repo.signingKey) return "The copy on this device is signed with another key"
        folder.mkdirs()
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
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                Files.move(download.toPath(), File(folder, "$packageName.apk").toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                commit { if (before == null) it.copy(trusted = it.trusted + (packageName to repo.signingKey)) else it }
            }
            return null
        } finally {
            download.delete()
        }
    }

    private suspend fun commit(change: (ExtensionChoices) -> ExtensionChoices) {
        choices.update(change)
        onChanged()
    }

    private suspend fun index(repo: ExtensionRepo, refresh: Boolean): RepoIndex {
        val index = indexes[repo.url]?.takeUnless { refresh } ?: RepoReader.read(repo.url, ::get).also { indexes[repo.url] = it }
        if (index.signingKey != repo.signingKey) throw RepoProblem("Now signed with another key; remove it and add it again to trust that key")
        return index
    }

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

    private suspend fun get(url: String): ByteArray = client.newCall(request(url)).awaitSuccess().use { response ->
        if (response.body.contentLength() > MAX_INDEX_BYTES) throw RepoProblem(TOO_LARGE)
        response.body.byteStream().use { readAtMost(it, MAX_INDEX_BYTES) }
    }

    private suspend fun download(url: String, file: File) {
        client.newCall(request(url)).awaitSuccess().use { response ->
            if (response.body.contentLength() > MAX_APK_BYTES) throw RepoProblem(TOO_LARGE)
            response.body.byteStream().use { input -> file.outputStream().use { output -> copyAtMost(input, output, MAX_APK_BYTES) } }
        }
    }

    private fun request(url: String): Request = try {
        Request.Builder().url(url).build()
    } catch (_: IllegalArgumentException) {
        throw RepoProblem("Not a web address")
    }

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

internal class RepoProblem(message: String) : Exception(message)

internal const val TOO_LARGE = "Too large"

internal const val MAX_INDEX_BYTES = 8L * 1024 * 1024

internal fun copyAtMost(input: InputStream, output: OutputStream, limit: Long) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var copied = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) return
        copied += read
        if (copied > limit) throw RepoProblem(TOO_LARGE)
        output.write(buffer, 0, read)
    }
}

internal fun readAtMost(input: InputStream, limit: Long): ByteArray = ByteArrayOutputStream().also { copyAtMost(input, it, limit) }.toByteArray()
