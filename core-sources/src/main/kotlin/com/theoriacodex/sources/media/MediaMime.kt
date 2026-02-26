package com.theoriacodex.sources.media

private val IMAGE_MIME_BY_EXT = mapOf(
    "gif" to "image/gif",
    "png" to "image/png",
    "webp" to "image/webp",
    "jpg" to "image/jpeg",
    "jpeg" to "image/jpeg",
    "bmp" to "image/bmp",
    "heic" to "image/heic",
    "heif" to "image/heif",
    "avif" to "image/avif",
)

private val VIDEO_MIME_BY_EXT = mapOf(
    "mp4" to "video/mp4",
    "webm" to "video/webm",
    "mov" to "video/quicktime",
    "m4v" to "video/x-m4v",
)

fun mimeFromFileExt(ext: String?): String? {
    val normalized = ext?.trim()?.lowercase()?.removePrefix(".") ?: return null
    return IMAGE_MIME_BY_EXT[normalized] ?: VIDEO_MIME_BY_EXT[normalized]
}

fun inferMimeFromUrl(url: String?): String? {
    val normalized = url
        ?.substringBefore('?')
        ?.substringBefore('#')
        ?.trim()
        ?.lowercase()
        ?: return null
    val ext = normalized.substringAfterLast('.', "")
    if (ext.isBlank()) return null
    return mimeFromFileExt(ext)
}
