package com.theoriacodex.domain.recommendation

import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.tags.decodeHtmlEntities
import com.theoriacodex.domain.tags.sourceTagKey
import java.text.Normalizer
import java.util.Locale

/** One Like's training tags, spelled in its own source's vocabulary. */
data class LikedTagDocument(
    val source: SourceKey,
    val tags: List<String>,
)

/**
 * Source-neutral spelling used only to compare tags across sources. It follows Danbooru's own
 * Pixiv-tag normalization: NFKC, lowercase, and `_` or whitespace runs collapsed to one space.
 */
fun crossSourceTagKey(tag: String): String =
    Normalizer.normalize(decodeHtmlEntities(tag), Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .replace('_', ' ')
        .replace(CROSS_SOURCE_WHITESPACE_REGEX, " ")
        .trim()

/**
 * Native Pixiv tags and their English translations, as concept keys. Built from the tag
 * suggestion store, which learns pairs from Pixiv search results, autocomplete and trending.
 */
class PixivTagDictionary private constructor(
    private val keyByNative: Map<String, String>,
    private val nativeByKey: Map<String, String>,
) {
    val isEmpty: Boolean get() = keyByNative.isEmpty()

    fun conceptKey(nativeTag: String): String? = keyByNative[sourceTagKey(SourceKey.PIXIV, nativeTag)]

    fun nativeTag(conceptKey: String): String? = nativeByKey[conceptKey]

    companion object {
        val Empty = PixivTagDictionary(emptyMap(), emptyMap())

        /** [pairs] are (native, translation), best first: a concept keeps its first native tag. */
        fun from(pairs: Iterable<Pair<String, String>>): PixivTagDictionary {
            val keyByNative = mutableMapOf<String, String>()
            val nativeByKey = mutableMapOf<String, String>()
            pairs.forEach { (native, translation) ->
                val nativeTag = native.trim()
                val key = crossSourceTagKey(translation)
                if (nativeTag.isEmpty() || key.isEmpty()) return@forEach
                keyByNative.putIfAbsent(sourceTagKey(SourceKey.PIXIV, nativeTag), key)
                nativeByKey.putIfAbsent(key, nativeTag)
            }
            return PixivTagDictionary(keyByNative, nativeByKey)
        }
    }
}

/**
 * Training documents for For You when Likes on one tag-compatible source should teach the
 * others. A target source trains on its own Likes first, then on other sources' Likes translated
 * into its vocabulary: booru tags map by concept key, and Pixiv maps through [dictionary].
 * Tags that cannot be mapped are dropped. Foreign documents are capped at
 * `max(native, FOREIGN_DOCUMENT_FLOOR)`, so they bridge a cold start but never outweigh what the
 * user liked on the target itself.
 *
 * [likes] should be ordered most recent first; each document is translated at most once.
 */
class CrossSourceTraining(
    likes: List<LikedTagDocument>,
    private val dictionary: PixivTagDictionary,
) {
    private val documents = likes.map(::Document)

    fun documentsFor(target: SourceKey): List<List<String>> {
        val native = documents.filter { it.like.source == target }.map { it.like.tags }
        if (target !in SHARED_VOCABULARY_SOURCES) return native
        val foreign = documents.asSequence()
            .filter { canMap(from = it.like.source, to = target) }
            .map { document -> document.conceptKeys.mapNotNull { key -> spell(key, target) }.distinct() }
            .filter { it.isNotEmpty() }
            .take(maxOf(native.size, FOREIGN_DOCUMENT_FLOOR))
            .toList()
        return native + foreign
    }

    private fun canMap(from: SourceKey, to: SourceKey): Boolean =
        from != to && from in SHARED_VOCABULARY_SOURCES &&
            (!dictionary.isEmpty || (from != SourceKey.PIXIV && to != SourceKey.PIXIV))

    private fun spell(conceptKey: String, target: SourceKey): String? =
        if (target == SourceKey.PIXIV) dictionary.nativeTag(conceptKey) else conceptKey.replace(' ', '_')

    private inner class Document(val like: LikedTagDocument) {
        val conceptKeys: List<String> by lazy {
            like.tags.mapNotNull { tag ->
                if (like.source == SourceKey.PIXIV) dictionary.conceptKey(tag) else crossSourceTagKey(tag)
            }.filter(String::isNotEmpty)
        }
    }

    companion object {
        /** Sources whose tags share one English vocabulary, directly or through Pixiv translations. */
        val SHARED_VOCABULARY_SOURCES: Set<SourceKey> = setOf(
            SourceKey.PIXIV, SourceKey.DANBOORU, SourceKey.AIBOORU, SourceKey.GELBOORU, SourceKey.RULE34XXX,
        )
    }
}

private const val FOREIGN_DOCUMENT_FLOOR = 24
private val CROSS_SOURCE_WHITESPACE_REGEX = Regex("\\s+")
