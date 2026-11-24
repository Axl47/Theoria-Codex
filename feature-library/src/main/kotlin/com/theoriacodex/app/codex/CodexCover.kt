package com.theoriacodex.app.codex

import com.theoriacodex.app.media.normalizeMediaUrl
import com.theoriacodex.app.media.previewMediaDeliveryPlan
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SourceKey
import java.io.File

internal fun resolveCodexCoverCandidates(
    storageDirectory: File,
    posts: List<Post>,
    thumbnailFiles: List<File> = storageDirectory.resolve("cache/thumbnails").listFiles().orEmpty().toList(),
): List<CodexCoverCandidate> {
    val thumbnailDirectory = storageDirectory.resolve("cache/thumbnails")
    return buildList {
        posts.take(MAX_CODEX_COVER_POSTS).forEach { post ->
            addAll(
                buildList {
                    addPostCoverCandidates(
                        post = post,
                        thumbnailDirectory = thumbnailDirectory,
                        thumbnailFiles = thumbnailFiles,
                    )
                }.distinct().take(MAX_CODEX_COVER_CANDIDATES_PER_POST),
            )
        }
    }.distinct()
}

fun resolveNamedCodexCoverCandidates(
    storageDirectory: File,
    name: String,
    cacheRevision: Long = 0L,
    thumbnailFiles: List<File> = storageDirectory.resolve("cache/thumbnails").listFiles().orEmpty().toList(),
): List<CodexCoverCandidate> {
    val thumbnailDirectory = storageDirectory.resolve("cache/thumbnails")
    val prefix = "${name}_"
    val customSources = thumbnailFiles.asSequence()
        .filter { file -> file.name.startsWith(prefix) }
        .mapNotNull { file -> SourceKey.parse(file.name.removePrefix(prefix).substringBefore('.')) }
        .filter(SourceKey::isCustom)
    val sources = (SourceKey.builtIns.asSequence() + customSources).distinct().sorted().toList()
    return sources.flatMap { source ->
        val key = "$prefix${source.id}"
        buildList {
            thumbnailFiles
                .firstOrNull { file ->
                    file.isFile && file.name.startsWith("$key.") && file.extension != "url"
                }
                ?.let { add(CodexCoverCandidate.LocalFile(it, cacheRevision)) }
            thumbnailDirectory.resolve("$key.url")
                .takeIf(File::exists)
                ?.let { runCatching { it.readText().trim() }.getOrNull() }
                ?.takeIf(String::isNotBlank)
                ?.let { addLocationCandidate(source, it, cacheRevision) }
        }
    }
}

private fun MutableList<CodexCoverCandidate>.addPostCoverCandidates(
    post: Post,
    thumbnailDirectory: File,
    thumbnailFiles: List<File>,
) {
    val key = "${post.id.source.id}_${post.id.sourcePostId}"
    thumbnailFiles
        .firstOrNull { file ->
            file.isFile && file.name.startsWith("$key.") && !file.name.endsWith(".url")
        }
        ?.let { add(CodexCoverCandidate.LocalFile(it)) }

    val remotePointer = thumbnailDirectory.resolve("$key.url")
    if (remotePointer.exists()) {
        runCatching { remotePointer.readText().trim() }
            .getOrNull()
            ?.takeIf(String::isNotBlank)
            ?.let { addLocationCandidate(post.id.source, it) }
    }

    post.preview.localPath
        ?.takeIf(String::isNotBlank)
        ?.let(::File)
        ?.takeIf(File::exists)
        ?.let { add(CodexCoverCandidate.LocalFile(it)) }

    previewMediaDeliveryPlan(post).candidates.forEach { candidate ->
        addLocationCandidate(post.id.source, candidate.location)
    }
}

private fun MutableList<CodexCoverCandidate>.addLocationCandidate(
    source: SourceKey,
    location: String,
    cacheRevision: Long = 0L,
) {
    val normalized = normalizeMediaUrl(source, location)?.takeIf(String::isNotBlank) ?: return
    val localFile = File(normalized)
    if (localFile.exists()) {
        add(CodexCoverCandidate.LocalFile(localFile, cacheRevision))
    } else {
        add(CodexCoverCandidate.RemoteImage(source = source, url = normalized, cacheRevision = cacheRevision))
    }
}

private const val MAX_CODEX_COVER_POSTS = 8
private const val MAX_CODEX_COVER_CANDIDATES_PER_POST = 8
