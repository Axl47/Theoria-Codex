package com.theoriacodex.domain.series

data class ExtensionRepo(
    val url: String,
    val name: String,
    val signingKey: String,
)

data class NewExtensionRepo(val url: String, val signingKey: String? = null)

fun storedRepoAddress(url: String): String {
    val trimmed = url.trim().trimEnd('/')
    return LEGACY_REPO_FILES.firstNotNullOfOrNull { file -> trimmed.removeSuffix("/$file").takeIf { it != trimmed } } ?: trimmed
}

private val LEGACY_REPO_FILES = listOf("index.min.json", "repo.json")

data class RepoExtension(
    val repo: String,
    val packageName: String,
    val name: String,
    val languages: List<String>,
    val versionName: String,
    val kind: SeriesKind,
    val status: Status,
    val iconUrl: String? = null,
) {
    enum class Status {
        AVAILABLE,

        INSTALLED,

        UPDATE,

        OTHER_KEY,
    }
}

data class RepoListing(val extensions: List<RepoExtension>, val problems: Map<String, String> = emptyMap())

interface ExtensionRepos {
    suspend fun repos(): List<ExtensionRepo>

    suspend fun add(repos: List<NewExtensionRepo>): Map<String, String>

    suspend fun restore(repos: List<ExtensionRepo>): Map<String, String>

    suspend fun remove(url: String)

    suspend fun available(refresh: Boolean = false): RepoListing

    suspend fun install(repo: String, packageName: String): String?

    suspend fun uninstall(id: String)
}
