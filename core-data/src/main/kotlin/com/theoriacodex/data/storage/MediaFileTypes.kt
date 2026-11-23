package com.theoriacodex.data.storage

/** The file extension of each media type the app saves copies of; a saved file's extension is how its type is read back. */
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

/** The extension a saved copy gets: its media type's, else the one its address ends in, else `bin`. */
fun mediaFileExtension(mime: String?, url: String?): String =
    EXTENSIONS[mime?.substringBefore(';')?.trim()?.lowercase()]
        ?: url?.substringBefore('#')?.substringBefore('?')?.substringAfterLast('/')?.substringAfterLast('.', "")
            ?.takeIf { it.matches(ADDRESS_EXTENSION) }
        ?: "bin"
