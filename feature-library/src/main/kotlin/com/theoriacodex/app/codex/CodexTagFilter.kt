package com.theoriacodex.app.codex

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import com.theoriacodex.app.tags.TagCount
import com.theoriacodex.app.tags.postTagKeys
import com.theoriacodex.app.tags.topTags
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.tags.normalizeMatchToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun codexTags(posts: List<Post>): List<TagCount> = topTags(
    posts,
    limit = Int.MAX_VALUE,
    counted = 1 until posts.size,
    includeTerm = ::isCarriedTag,
    keyOf = ::normalizeMatchToken,
    tieOrder = compareBy { it.tag.lowercase() },
)

@Composable
internal fun rememberCodexTags(posts: List<Post>): State<List<TagCount>?> =
    produceState<List<TagCount>?>(null, posts) { value = withContext(Dispatchers.Default) { codexTags(posts) } }

internal fun List<Post>.withCodexTags(keys: Collection<String>): List<Post> =
    if (keys.isEmpty()) this else filter { post -> codexTagKeys(post).keys.containsAll(keys) }

internal fun codexTagKeys(post: Post): Map<String, String> =
    postTagKeys(post, includeTerm = ::isCarriedTag, keyOf = ::normalizeMatchToken)

private fun isCarriedTag(term: SearchTerm): Boolean = !term.value.startsWith("-")

const val CODEX_TAG_CHIP_LIMIT = 12
