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

/**
 * Every tag [posts] carry, across every source, most common first, for Codex detail's tag chips and tag sheet.
 * Spellings that match alike count once per post; ties keep alphabetical order. Tags every post carries filter
 * nothing, so they are left out.
 */
internal fun codexTags(posts: List<Post>): List<TagCount> = topTags(
    posts,
    limit = Int.MAX_VALUE,
    counted = 1 until posts.size,
    includeTerm = ::isCarriedTag,
    keyOf = ::normalizeMatchToken,
    tieOrder = compareBy { it.tag.lowercase() },
)

/**
 * [codexTags] of [posts], computed off the main thread: null until the first list is ready, then the previous list
 * until the next one is.
 */
@Composable
internal fun rememberCodexTags(posts: List<Post>): State<List<TagCount>?> =
    produceState<List<TagCount>?>(null, posts) { value = withContext(Dispatchers.Default) { codexTags(posts) } }

/** The posts carrying every tag whose match key is in [keys]; every post when [keys] is empty. */
internal fun List<Post>.withCodexTags(keys: Collection<String>): List<Post> =
    if (keys.isEmpty()) this else filter { post -> codexTagKeys(post).keys.containsAll(keys) }

/** [post]'s tags by match key, as [codexTags] counts them. */
internal fun codexTagKeys(post: Post): Map<String, String> =
    postTagKeys(post, includeTerm = ::isCarriedTag, keyOf = ::normalizeMatchToken)

/** Negated terms are exclusions, not tags the post carries. */
private fun isCarriedTag(term: SearchTerm): Boolean = !term.value.startsWith("-")

/** How many of a Codex's most common tags its chip row offers as shortcuts. */
const val CODEX_TAG_CHIP_LIMIT = 12
