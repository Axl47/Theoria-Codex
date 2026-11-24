package com.theoriacodex.domain.model

const val PIXIV_UGOIRA_MIME: String = "image/ugoira"

data class PostId(
    val source: SourceKey,
    val sourcePostId: String,
)

data class CreatorProfile(
    val source: SourceKey,
    val displayName: String,
    val profileId: String? = null,
    val profileUrl: String? = null,
    val uploadsQuery: String? = null,
)

data class VideoVariant(val url: String, val height: Int? = null, val original: Boolean = false, val mime: String? = null)

enum class VideoQuality { AUTO, DATA_SAVER, BEST }

data class MediaStream(
    val url: String,
    val label: String,
    val mime: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val subtitles: List<MediaTrack> = emptyList(),
    val audioTracks: List<MediaTrack> = emptyList(),
)

data class MediaTrack(val url: String, val label: String)

data class ImageRef(
    val url: String?,
    val localPath: String?,
    val mime: String?,
    val progressiveUrls: List<String> = emptyList(),
    val isAnimated: Boolean = false,
    val videoVariants: List<VideoVariant> = emptyList(),
    val thumbnailUrl: String? = null,
    val fallbackUrls: List<String> = emptyList(),
    val streams: List<MediaStream> = emptyList(),
)

fun List<MediaStream>.playable(): List<MediaStream> {
    val streams = filter { it.url.isNotBlank() }
    return streams.zip(uniqueLabels(streams.map { it.label }) { "Stream $it" }) { stream, label -> stream.copy(label = label) }
}

fun uniqueLabels(labels: List<String>, unnamed: (place: Int) -> String): List<String> {
    val named = labels.mapIndexed { index, raw -> raw.trim().ifEmpty { unnamed(index + 1) } }
    val taken = named.toHashSet()
    val seen = HashSet<String>()
    return named.map { label ->
        if (seen.add(label)) return@map label
        var count = 2
        while (!taken.add("$label ($count)")) count++
        "$label ($count)"
    }
}

data class PostTaxonomyTerm(
    val value: String,
    val facet: SearchFacet = SearchFacet.TAG,
    val sourceNamespace: String? = null,
    val count: Int? = null,
    val translation: String? = null,
) {
    fun toSearchTerm(): SearchTerm {
        return SearchTerm(
            value = value,
            facet = facet,
            sourceNamespace = sourceNamespace,
        )
    }
}

data class Post(
    val id: PostId,
    val preview: ImageRef,
    val full: ImageRef?,
    val media: List<ImageRef> = emptyList(),
    val pageUrl: String?,
    val width: Int?,
    val height: Int?,
    val canonicalTags: List<String>,
    val rawTags: List<String>,
    val authorName: String?,
    val createdAtEpochMs: Long?,
    val title: String? = null,
    val creatorProfile: CreatorProfile? = null,
    val durationMs: Long? = null,
    val mediaCount: Int? = null,
    val galleryLengthUnknown: Boolean = false,
    val taxonomy: List<PostTaxonomyTerm> = canonicalTags.map { value ->
        PostTaxonomyTerm(value = value)
    },
    val creatorProfiles: List<CreatorProfile> = listOfNotNull(creatorProfile),
    val commentHint: CommentHint? = null,
    val contentHash: String? = null,
    val pictureHash: Long? = null,
    val originUrl: String? = null,
)

fun contentHashOrNull(raw: String?): String? =
    raw?.trim()?.lowercase()?.takeIf { hash -> hash.length == MD5_HEX_LENGTH && hash.all { it in '0'..'9' || it in 'a'..'f' } }

val Post.fileHash: String?
    get() = contentHash ?: fileUrls().firstNotNullOfOrNull(::md5InUrlPath)

private fun Post.fileUrls(): Sequence<String> = sequence {
    for (image in listOfNotNull(full) + media + preview) {
        image.url?.let { yield(it) }
        yieldAll(image.fallbackUrls)
        yieldAll(image.progressiveUrls)
        image.thumbnailUrl?.let { yield(it) }
    }
}

private fun md5InUrlPath(url: String): String? {
    val path = url.substringBefore('?').substringBefore('#').substringAfter("://").substringAfter('/', "")
    return path.split('/').filter(String::isNotEmpty).takeLast(2).asReversed()
        .firstNotNullOfOrNull { segment -> MD5_IN_PATH.find(segment)?.value?.lowercase() }
}

private val MD5_IN_PATH = Regex("(?<![0-9a-fA-F])[0-9a-fA-F]{32}(?![0-9a-fA-F])")

fun Post.keepingListingDataOf(listed: Post): Post {
    val hint = commentHint ?: listed.commentHint
    val hash = contentHash ?: listed.contentHash
    val origin = originUrl ?: listed.originUrl
    val picture = pictureHash ?: listed.pictureHash
    if (hint == commentHint && hash == contentHash && origin == originUrl && picture == pictureHash) return this
    return copy(commentHint = hint, contentHash = hash, originUrl = origin, pictureHash = picture)
}

fun Iterable<Post>.distinctByFile(): List<Post> = FileSieve().let { sieve -> filter(sieve::admit) }

fun List<Post>.plusDistinctFiles(incoming: Iterable<Post>): List<Post> {
    val sieve = FileSieve()
    forEach(sieve::remember)
    return this + incoming.filter(sieve::admit)
}

private class FileSieve {
    private val ids = HashSet<PostId>()
    private val files = HashSet<String>()
    private val pictures = PictureHashIndex<Unit>()

    fun admit(post: Post): Boolean = ids.add(post.id) && (post.contentHash == null || files.add(post.contentHash)) &&
        (post.pictureHash == null || pictures.addUnlessMatched(Unit, post.pictureHash))

    fun remember(post: Post) {
        ids += post.id
        post.contentHash?.let(files::add)
        post.pictureHash?.let { pictures.add(Unit, it) }
    }
}

private const val MD5_HEX_LENGTH = 32

fun Post.withInferredGalleryLength(): Post {
    val sparseGallery = (id.source == SourceKey.NHENTAI || id.source == SourceKey.HITOMI) &&
        media.isEmpty() && full == null && mediaCount == null
    return if (sparseGallery && !galleryLengthUnknown) copy(galleryLengthUnknown = true) else this
}
