package com.theoriacodex.app.media

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
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
    return copyTextToClipboard(context, "tags", formatPostTagsForClipboard(post))
}

fun copyPostUrlToClipboard(context: Context, post: Post): Boolean {
    val pageUrl = post.pageUrl?.trim().takeIf { !it.isNullOrBlank() } ?: return false
    return copyTextToClipboard(context, "post_url", pageUrl)
}

fun copyTextToClipboard(context: Context, label: String, text: String): Boolean {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    return true
}

fun showClipboardCopyConfirmation(context: Context, message: String) {
    appClipboardConfirmationMessage(message)?.let { confirmation ->
        Toast.makeText(context, confirmation, Toast.LENGTH_SHORT).show()
    }
}

internal fun appClipboardConfirmationMessage(
    message: String,
    sdkInt: Int = Build.VERSION.SDK_INT,
): String? = message.takeIf { sdkInt <= Build.VERSION_CODES.S_V2 }
