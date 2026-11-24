package com.theoriacodex.domain.series

/**
 * A repo the user added: an address publishing an index of extensions, the same repos Mihon and Aniyomi use. Only
 * APKs signed by [signingKey] (the SHA-256 of the signing certificate, lowercase hex, pinned when the repo was added)
 * are installed from it.
 */
data class ExtensionRepo(
    /** The folder holding `repo.json` and `index.min.json`, or the address of a newer repo's own index. */
    val url: String,
    val name: String,
    val signingKey: String,
)

/**
 * A repo to add: [url] is the `index.min.json` address users paste, the folder holding it (as backups store it), or a
 * newer repo's index. [signingKey], when known (backups carry it), must be the key the repo names now.
 */
data class NewExtensionRepo(val url: String, val signingKey: String? = null)

/**
 * How a repo address is stored as [ExtensionRepo.url], and so what two addresses for one repo share: the folder for a
 * legacy repo's `index.min.json` or `repo.json`, any other address as given.
 */
fun storedRepoAddress(url: String): String {
    val trimmed = url.trim().trimEnd('/')
    return LEGACY_REPO_FILES.firstNotNullOfOrNull { file -> trimmed.removeSuffix("/$file").takeIf { it != trimmed } } ?: trimmed
}

private val LEGACY_REPO_FILES = listOf("index.min.json", "repo.json")

/** An extension a repo offers, and how the device stands with it. */
data class RepoExtension(
    /** The [ExtensionRepo.url] of the repo offering it. */
    val repo: String,
    val packageName: String,
    val name: String,
    /** The languages of its sources; one extension can have dozens. */
    val languages: List<String>,
    val versionName: String,
    val kind: SeriesKind,
    val status: Status,
) {
    enum class Status {
        /** Not on the device. */
        AVAILABLE,

        /** On the device, signed by this repo's key, at this version or newer. */
        INSTALLED,

        /** On the device, signed by this repo's key, at an older version. */
        UPDATE,

        /** On the device signed by another key, so this repo's copy cannot replace it. */
        OTHER_KEY,
    }
}

/** What the repos offer, with why each repo that could not be read was skipped, keyed by its [ExtensionRepo.url]. */
data class RepoListing(val extensions: List<RepoExtension>, val problems: Map<String, String> = emptyMap())

/**
 * The user's extension repos, and installing, updating and removing extensions from them. Extensions are installed
 * into this app's own storage, not as Android packages, so no other app can remove or replace them and Android asks
 * nothing. Adding a repo installs and turns on nothing; installing an extension that is not on the device turns that
 * one extension on, and an update keeps its choice. Calls can take seconds and use the network; each moves off the
 * main thread itself.
 */
interface ExtensionRepos {
    suspend fun repos(): List<ExtensionRepo>

    /**
     * Adds each repo after reading what it publishes now; one already added at the same address counts as added.
     * Returns, keyed by the [NewExtensionRepo.url] given, why each other one was not.
     */
    suspend fun add(repos: List<NewExtensionRepo>): Map<String, String>

    suspend fun remove(url: String)

    /** The extensions the repos offer that this app can run. Reads each repo once per process unless [refresh]. */
    suspend fun available(refresh: Boolean = false): RepoListing

    /**
     * Downloads [packageName] from [repo], checks it is that package signed by the repo's key and not older than the
     * copy on the device, and installs it. Returns why it was not installed, or null once it is.
     */
    suspend fun install(repo: String, packageName: String): String?

    /** Removes the extension [id] names ([InstalledExtension.id]) when this app installed it, and turns it off. */
    suspend fun uninstall(id: String)
}
