package com.theoriacodex.sources.nhentai

private val titleAnnotations = Regex("\\([^()]*\\)|\\[[^\\[\\]]*\\]|\\{[^{}]*\\}")
private val titleWhitespace = Regex("\\s+")

internal fun cleanNhentaiTitle(title: String?): String? {
    var cleaned = title ?: return null
    // Remove innermost annotations first so nested brackets are removed in full.
    do {
        val previous = cleaned
        cleaned = titleAnnotations.replace(previous, "")
    } while (cleaned != previous)
    return titleWhitespace.replace(cleaned, " ").trim().takeIf(String::isNotBlank)
}
