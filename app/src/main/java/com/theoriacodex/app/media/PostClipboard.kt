package com.theoriacodex.app.media

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.theoriacodex.domain.model.Post

fun formatPostTagsForClipboard(post: Post): String {
    val canonicalPositives = post.canonicalTags.filterNot { it.startsWith("-") }
    val canonicalNegatives = post.canonicalTags
        .filter { it.startsWith("-") }
        .map { it.removePrefix("-") }

    val rawPositives = post.rawTags.filterNot { it.startsWith("-") }
    val rawNegatives = post.rawTags
        .filter { it.startsWith("-") }
        .map { it.removePrefix("-") }

    val positives = (canonicalPositives + rawPositives)
        .map { it.trim() }
        .filter { it.isNotBlank() && !it.startsWith("-") }
        .distinct()
    val negatives = (canonicalNegatives + rawNegatives)
        .map { it.trim().removePrefix("-") }
        .filter { it.isNotBlank() }
        .distinct()

    val positiveLine = positives.joinToString(", ")
    val negativeLine = negatives.joinToString(", ") { "-$it" }
    return "$positiveLine\n\n$negativeLine"
}

fun copyPostTagsToClipboard(context: Context, post: Post): Boolean {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
    clipboard.setPrimaryClip(ClipData.newPlainText("tags", formatPostTagsForClipboard(post)))
    return true
}

fun copyPostUrlToClipboard(context: Context, post: Post): Boolean {
    val pageUrl = post.pageUrl?.trim().takeIf { !it.isNullOrBlank() } ?: return false
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
    clipboard.setPrimaryClip(ClipData.newPlainText("post_url", pageUrl))
    return true
}
