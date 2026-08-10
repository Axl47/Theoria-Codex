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
import com.theoriacodex.app.media.MediaRequestFactory
import com.theoriacodex.app.media.PostMediaKind
import com.theoriacodex.app.media.mediaKind
import com.theoriacodex.app.media.normalizeMediaUrl
import com.theoriacodex.app.media.postPreviewImageCandidate
import com.theoriacodex.app.media.progressiveImageCandidates
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SourceKey
import java.io.File

internal sealed interface CodexCoverCandidate {
    data class LocalFile(val file: File) : CodexCoverCandidate

    data class RemoteImage(
        val source: SourceKey,
        val url: String,
    ) : CodexCoverCandidate
}

internal fun resolveCodexCoverCandidates(
    storageDirectory: File,
    posts: List<Post>,
): List<CodexCoverCandidate> {
    val thumbnailDirectory = storageDirectory.resolve("cache/thumbnails")
    val thumbnailFiles = thumbnailDirectory.listFiles().orEmpty().toList()
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

    buildList {
        postPreviewImageCandidate(post)?.ref?.let(::add)
        add(post.preview)
        post.full?.let(::add)
        addAll(post.media)
    }
        .distinct()
        .filterNot { ref -> mediaKind(ref) == PostMediaKind.VIDEO }
        .flatMap { ref -> progressiveImageCandidates(post, ref) }
        .forEach { location -> addLocationCandidate(post.id.source, location) }
}

private fun MutableList<CodexCoverCandidate>.addLocationCandidate(
    source: SourceKey,
    location: String,
) {
    val normalized = normalizeMediaUrl(source, location)?.takeIf(String::isNotBlank) ?: return
    val localFile = File(normalized)
    if (localFile.exists()) {
        add(CodexCoverCandidate.LocalFile(localFile))
    } else {
        add(CodexCoverCandidate.RemoteImage(source = source, url = normalized))
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

private fun CodexCoverCandidate.toImageModel(context: Context): Any = when (this) {
    is CodexCoverCandidate.LocalFile -> file
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
