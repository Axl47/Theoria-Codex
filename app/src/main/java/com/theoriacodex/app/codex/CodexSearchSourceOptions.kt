package com.theoriacodex.app.codex

import com.theoriacodex.app.source.SourcePresentationCatalog
import com.theoriacodex.app.tags.isGeneralPostTag
import com.theoriacodex.app.tags.postActionTerms
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.tags.sourceTagKey
import kotlin.random.Random

data class CodexSearchSourceOption(
    val source: SourceKey,
    val postCount: Int,
)

data class CodexSearchTagOption(
    val tag: String,
    val count: Int,
)

fun codexSearchSourceOptions(
    posts: List<Post>,
    availableSources: Set<SourceKey>,
): List<CodexSearchSourceOption> {
    if (posts.isEmpty() || availableSources.isEmpty()) return emptyList()

    val countsBySource = posts
        .asSequence()
        .map { post -> post.id.source }
        .filter { source -> source in availableSources }
        .groupingBy { source -> source }
        .eachCount()

    return SourcePresentationCatalog.orderedPresentations()
        .mapNotNull { presentation ->
            val source = presentation.source
            val count = countsBySource[source] ?: return@mapNotNull null
            CodexSearchSourceOption(source = source, postCount = count)
        }
}

fun codexSearchTagOptions(
    posts: List<Post>,
    source: SourceKey,
): List<CodexSearchTagOption> {
    if (posts.isEmpty()) return emptyList()

    val frequencies = linkedMapOf<String, CodexTagFrequency>()
    posts
        .asSequence()
        .filter { post -> post.id.source == source }
        .forEach { post ->
            val uniquePostTags = linkedMapOf<String, String>()
            for (rawTag in post.canonicalTags) {
                val tag = rawTag.trim()
                if (tag.isBlank() || tag.startsWith("-")) continue
                val key = sourceTagKey(source, tag)
                if (key.isBlank()) continue
                uniquePostTags.putIfAbsent(key, tag)
            }

            uniquePostTags.forEach { (key, tag) ->
                val current = frequencies[key]
                if (current == null) {
                    frequencies[key] = CodexTagFrequency(tag = tag, count = 1)
                } else {
                    frequencies[key] = current.copy(count = current.count + 1)
                }
            }
        }

    return frequencies
        .values
        .sortedWith(
            compareByDescending<CodexTagFrequency> { it.count }
                .thenBy { it.tag.lowercase() }
        )
        .map { frequency ->
            CodexSearchTagOption(
                tag = frequency.tag,
                count = frequency.count,
            )
        }
}

/** One saved post's tags, the pool that "Draw" picks from. */
internal data class CodexDrawPost(val sourcePostId: String, val terms: List<SearchTerm>)

/** What the Codex search offers for one source: every typed tag with its post count, and the posts. */
internal data class CodexSearchTerms(
    val options: List<CodexSearchTermOption>,
    val posts: List<CodexDrawPost>,
)

internal data class CodexSearchTermOption(val term: SearchTerm, val count: Int)

internal fun codexSearchTerms(posts: List<Post>, source: SourceKey): CodexSearchTerms {
    val drawPosts = posts.filter { post -> post.id.source == source }.mapNotNull { post ->
        val terms = postActionTerms(post).filterNot { term -> term.value.startsWith("-") }
        terms.distinctBy { term -> codexTermKey(source, term) }
            .takeIf(List<SearchTerm>::isNotEmpty)
            ?.let { CodexDrawPost(post.id.sourcePostId, it) }
    }
    val options = drawPosts.flatMap(CodexDrawPost::terms)
        .groupBy { term -> codexTermKey(source, term) }
        .values
        .map { terms -> CodexSearchTermOption(terms.first(), terms.size) }
        .sortedWith(compareByDescending<CodexSearchTermOption> { it.count }.thenBy { it.term.value.lowercase() })
    return CodexSearchTerms(options, drawPosts)
}

private fun codexTermKey(source: SourceKey, term: SearchTerm) = Triple(
    term.facet,
    term.sourceNamespace?.lowercase()?.takeUnless { term.isGeneralPostTag() },
    sourceTagKey(source, term.value),
)

/**
 * A random saved post's tags, so at least that post matches when they are all required. Tags drawn
 * independently across a collection may never appear together.
 */
internal fun drawCodexTerms(posts: List<CodexDrawPost>, amount: Int, random: Random = Random): CodexDraw? {
    val post = posts.randomOrNull(random) ?: return null
    return CodexDraw(post.sourcePostId, post.terms.shuffled(random).take(amount))
}

internal data class CodexDraw(val sourcePostId: String, val terms: List<SearchTerm>)

private data class CodexTagFrequency(
    val tag: String,
    val count: Int,
)
