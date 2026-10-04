package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SourceKey
import java.net.URI

/** Device paths, credentials, and signed URLs are never portable document data. */
fun portablePostSnapshot(post: Post): Post = post.copy(
    preview = portableBackupImage(post.preview),
    full = post.full?.let(::portableBackupImage)?.takeIf { it.url != null },
    media = post.media.map(::portableBackupImage),
    pageUrl = portableBackupPageUrl(post),
    creatorProfile = post.creatorProfile?.let(::portableBackupCreator),
    creatorProfiles = post.creatorProfiles.map(::portableBackupCreator),
)

internal fun portableBackupCreator(creator: CreatorProfile): CreatorProfile {
    val numericId = creator.profileId?.takeIf { it.matches(Regex("[0-9]+")) }
    val profileUrl = if (creator.source == SourceKey.GELBOORU && numericId != null) {
        "https://gelbooru.com/index.php?page=account&s=profile&id=$numericId"
    } else portableMediaUrl(creator.profileUrl)
    return creator.copy(profileUrl = profileUrl)
}

private fun portableBackupImage(image: ImageRef): ImageRef = image.copy(
    url = portableMediaUrl(image.url),
    localPath = null,
    progressiveUrls = image.progressiveUrls.mapNotNull(::portableMediaUrl),
    videoVariants = image.videoVariants.mapNotNull { variant ->
        portableMediaUrl(variant.url)?.let { variant.copy(url = it) }
    },
)

fun portableMediaUrl(value: String?): String? = value?.takeIf { raw ->
    runCatching { URI(raw) }.getOrNull()?.let {
        it.scheme in setOf("https", "http") && !it.host.isNullOrBlank() &&
            it.rawUserInfo == null && it.rawQuery == null && it.rawFragment == null
    } == true
}

private fun portableBackupPageUrl(post: Post): String? {
    val host = when (post.id.source) {
        SourceKey.GELBOORU -> "gelbooru.com"
        SourceKey.RULE34XXX -> "rule34.xxx"
        else -> null
    }
    // These public post identities use query parameters; rebuild only their known canonical form.
    return if (host != null && post.id.sourcePostId.matches(Regex("[0-9]+"))) {
        "https://$host/index.php?page=post&s=view&id=${post.id.sourcePostId}"
    } else portableMediaUrl(post.pageUrl)
}

