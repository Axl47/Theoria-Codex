package com.theoriacodex.app.codex

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.theoriacodex.app.media.MediaRequestFactory
import com.theoriacodex.app.media.normalizeMediaUrl
import com.theoriacodex.app.media.previewMediaDeliveryPlan
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SourceKey
import java.io.File

internal sealed interface CodexCoverCandidate {
    data class LocalFile(val file: File, val cacheRevision: Long = 0L) : CodexCoverCandidate

    data class RemoteImage(
        val source: SourceKey,
        val url: String,
        val cacheRevision: Long = 0L,
    ) : CodexCoverCandidate
}

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

internal fun resolveNamedCodexCoverCandidates(
    storageDirectory: File,
    name: String,
    cacheRevision: Long = 0L,
): List<CodexCoverCandidate> {
    val thumbnailDirectory = storageDirectory.resolve("cache/thumbnails")
    return SourceKey.entries.flatMap { source ->
        val key = "${name}_${source.name}"
        buildList {
            thumbnailDirectory.listFiles().orEmpty()
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
    val key = "${post.id.source.name}_${post.id.sourcePostId}"
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

@Composable
internal fun CodexCoverImage(
    candidates: List<CodexCoverCandidate>,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    fallback: @Composable () -> Unit,
) {
    var candidateIndex by remember(candidates) { mutableIntStateOf(0) }
    val candidate = candidates.getOrNull(candidateIndex)
    if (candidate == null) {
        fallback()
        return
    }

    val context = LocalContext.current
    val model = remember(context, candidate) { candidate.toImageModel(context) }
    AsyncImage(
        model = model,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
        onError = {
            if (candidateIndex <= candidates.lastIndex) {
                candidateIndex += 1
            }
        },
    )
}

internal fun CodexCoverCandidate.toImageModel(context: Context): ImageRequest = when (this) {
    is CodexCoverCandidate.LocalFile -> ImageRequest.Builder(context).data(file)
        .memoryCacheKey("codex-cover:${file.absolutePath}:${file.lastModified()}:${file.length()}:$cacheRevision").build()
    is CodexCoverCandidate.RemoteImage -> MediaRequestFactory.imageRequest(
        context = context,
        url = url,
        sourceKey = source,
        crossfade = false,
        staticAnimatedWebPFrame = true,
    )
}

private const val MAX_CODEX_COVER_POSTS = 8
private const val MAX_CODEX_COVER_CANDIDATES_PER_POST = 8
