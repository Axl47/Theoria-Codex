package com.theoriacodex.data.storage

private val EXTENSIONS = mapOf(
    "image/ugoira" to "zip",
    "image/jpeg" to "jpg",
    "image/png" to "png",
    "image/webp" to "webp",
    "image/gif" to "gif",
    "image/avif" to "avif",
    "image/heic" to "heic",
    "image/heif" to "heif",
    "image/bmp" to "bmp",
    "video/mp4" to "mp4",
    "video/webm" to "webm",
)

private val ADDRESS_EXTENSION = Regex("[A-Za-z0-9]{1,8}")

fun mediaFileExtension(mime: String?, url: String?): String =
    EXTENSIONS[mime?.substringBefore(';')?.trim()?.lowercase()]
        ?: url?.substringBefore('#')?.substringBefore('?')?.substringAfterLast('/')?.substringAfterLast('.', "")
            ?.takeIf { it.matches(ADDRESS_EXTENSION) }
        ?: "bin"
