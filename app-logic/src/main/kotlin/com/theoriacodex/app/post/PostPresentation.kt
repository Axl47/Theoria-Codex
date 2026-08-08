package com.theoriacodex.app.post

import com.theoriacodex.domain.model.Post

/** Returns only meaningful provider-authored titles, never a post-ID stand-in. */
fun Post.displayTitleOrNull(): String? {
    return title
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.takeUnless { candidate -> candidate == id.sourcePostId.trim() }
}
