package com.theoriacodex.sources.hitomi

import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.sources.http.SourceHttpBodyTooLargeException
import com.theoriacodex.sources.http.SourceHttpClient
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CancellationException

/** Compiles typed terms into provider indexes while retaining the global-index version binding. */
internal class HitomiQueryCompiler(
    httpClient: SourceHttpClient,
    private val globalIndexCache: HitomiGlobalIndexCache,
) {
    private val globalSearchIndex = HitomiGlobalSearchIndex(httpClient)

    suspend fun compile(query: Query): CompiledHitomiQuery {
        val includes = query.includeTerms.normalizedDistinctTerms()
        val excludes = query.excludeTerms.normalizedDistinctTerms()
        (includes + excludes).forEach(::validateHitomiTerm)
        val includedLanguages = includes
            .filter { term -> term.facet == SearchFacet.LANGUAGE }
            .map(SearchTerm::value)
            .distinct()
        if (includedLanguages.size > 1) {
            return CompiledHitomiQuery(isUnsatisfiable = true)
        }
        val globalIndexVersion = if ((includes + excludes).any(SearchTerm::isPortableGeneralTag)) {
            loadGlobalIndexVersion()
        } else {
            null
        }
        val language = includedLanguages.singleOrNull() ?: "all"
        val positiveTerms = includes.filterNot { term -> term.facet == SearchFacet.LANGUAGE }
        val positiveIndexes = positiveTerms.map { term ->
            positiveIndex(term, query.sort, language, globalIndexVersion)
        }.distinctBy(CompiledIndex::key)
        val exclusionIndexes = excludes.map { term ->
            exclusionIndex(term, language, globalIndexVersion)
        }.distinctBy { index -> index.newestUrl }
        val base = CompiledIndex(
            key = "__all__",
            term = null,
            newestUrl = HitomiNozomi.urlFor(HitomiNozomiRequest(language = language)),
            sortedUrl = HitomiNozomi.urlFor(
                HitomiNozomiRequest(
                    language = language,
                    sort = query.sort.toHitomiNozomiSort(),
                ),
            ),
        )
        return CompiledHitomiQuery(
            base = base,
            positives = positiveIndexes,
            exclusions = exclusionIndexes.map { index -> index.forNewest() },
            globalIndexVersion = globalIndexVersion?.value,
        )
    }

    private suspend fun loadGlobalIndexVersion(): HitomiGlobalIndexVersion {
        return try {
            globalSearchIndex.currentVersion()
        } catch (error: CancellationException) {
            throw error
        } catch (error: SourceHttpBodyTooLargeException) {
            throw sourceParseFailure("Hitomi global search version exceeded its bounded response", error)
        } catch (error: IOException) {
            throw SourceAdapterException(
                reason = SourceFailureReason.NETWORK,
                message = "Hitomi global search version request failed",
                cause = error,
            )
        }
    }

    private suspend fun globalCompiledIndex(
        term: SearchTerm,
        version: HitomiGlobalIndexVersion,
    ): CompiledIndex {
        val normalized = term.value.trim().lowercase(Locale.ROOT)
        val url = "hitomi-global://${version.value}/${normalized.toByteArray().hitomiSha256Hex().take(16)}"
        val ids = globalIndexCache.getOrLoad(
            version = version.value,
            key = url,
        ) {
            try {
                val loaded = globalSearchIndex.galleryIds(normalized, version)
                if (loaded.version != version) {
                    throw HitomiProtocolException("global search returned an unexpected index version")
                }
                loaded.galleryIds
            } catch (error: CancellationException) {
                throw error
            } catch (error: SourceHttpBodyTooLargeException) {
                throw sourceParseFailure("Hitomi global search exceeded its bounded response", error)
            } catch (error: IOException) {
                throw SourceAdapterException(
                    reason = SourceFailureReason.NETWORK,
                    message = "Hitomi global search request failed",
                    cause = error,
                )
            }
        }
        return CompiledIndex(
            key = term.termCacheKey(),
            term = term,
            newestUrl = url,
            sortedUrl = url,
            inlineIds = ids,
        )
    }

    private fun validateHitomiTerm(term: SearchTerm) {
        val namespace = term.sourceNamespace ?: return
        val allowedNamespaces = when (term.facet) {
            SearchFacet.TAG -> setOf(
                HITOMI_TAG_NAMESPACE,
                HITOMI_FEMALE_NAMESPACE,
                HITOMI_MALE_NAMESPACE,
            )
            SearchFacet.ARTIST -> setOf(HITOMI_ARTIST_NAMESPACE)
            SearchFacet.CHARACTER -> setOf(HITOMI_CHARACTER_NAMESPACE)
            SearchFacet.SERIES -> setOf(HITOMI_SERIES_NAMESPACE)
            SearchFacet.GROUP -> setOf(HITOMI_GROUP_NAMESPACE)
            SearchFacet.TYPE -> setOf(HITOMI_TYPE_NAMESPACE)
            SearchFacet.LANGUAGE -> setOf(HITOMI_LANGUAGE_NAMESPACE)
        }
        if (namespace !in allowedNamespaces) {
            throw sourceParseFailure(
                "Hitomi does not support ${term.facet.name.lowercase()} terms in the $namespace namespace",
            )
        }
    }

    private fun SearchTerm.nozomiUrl(sort: HitomiNozomiSort, language: String): String {
        return HitomiNozomi.urlFor(toNozomiRequest(sort, language))
    }

    private fun SearchTerm.toNozomiRequest(
        sort: HitomiNozomiSort,
        language: String,
    ): HitomiNozomiRequest {
        val normalizedNamespace = sourceNamespace?.lowercase(Locale.ROOT)
        val (area, tag) = when (facet) {
            SearchFacet.TAG -> {
                val prefix = normalizedNamespace
                    ?.takeIf { value -> value == HITOMI_FEMALE_NAMESPACE || value == HITOMI_MALE_NAMESPACE }
                HITOMI_TAG_NAMESPACE to if (prefix == null) value else "$prefix:$value"
            }
            SearchFacet.ARTIST -> HITOMI_ARTIST_NAMESPACE to value
            SearchFacet.CHARACTER -> HITOMI_CHARACTER_NAMESPACE to value
            SearchFacet.SERIES -> HITOMI_SERIES_NAMESPACE to value
            SearchFacet.GROUP -> HITOMI_GROUP_NAMESPACE to value
            SearchFacet.TYPE -> HITOMI_TYPE_NAMESPACE to value
            SearchFacet.LANGUAGE -> "all" to "index"
        }
        return HitomiNozomiRequest(area = area, tag = tag, language = language, sort = sort)
    }

    private suspend fun positiveIndex(
        term: SearchTerm,
        sort: SortMode,
        language: String,
        version: HitomiGlobalIndexVersion?,
    ): CompiledIndex {
        if (term.isPortableGeneralTag) return globalCompiledIndex(term, requireNotNull(version))
        return CompiledIndex(
            key = term.termCacheKey(),
            term = term,
            newestUrl = term.nozomiUrl(HitomiNozomiSort.NEWEST, language),
            sortedUrl = term.nozomiUrl(sort.toHitomiNozomiSort(), language),
        )
    }

    private suspend fun exclusionIndex(
        term: SearchTerm,
        language: String,
        version: HitomiGlobalIndexVersion?,
    ): CompiledIndex {
        if (term.isPortableGeneralTag) return globalCompiledIndex(term, requireNotNull(version))
        val request = if (term.facet == SearchFacet.LANGUAGE) {
            HitomiNozomiRequest(language = term.value, sort = HitomiNozomiSort.NEWEST)
        } else {
            term.toNozomiRequest(HitomiNozomiSort.NEWEST, language)
        }
        val url = HitomiNozomi.urlFor(request)
        return CompiledIndex(key = term.termCacheKey(), term = term, newestUrl = url, sortedUrl = url)
    }

    private fun sourceParseFailure(message: String, cause: Throwable? = null): SourceAdapterException =
        SourceAdapterException(reason = SourceFailureReason.PARSE, message = message, cause = cause)
}

internal data class CompiledHitomiQuery(
    val base: CompiledIndex? = null,
    val positives: List<CompiledIndex> = emptyList(),
    val exclusions: List<CompiledIndex> = emptyList(),
    val globalIndexVersion: String? = null,
    val isUnsatisfiable: Boolean = false,
) {
    fun primaryOptions(sort: SortMode): List<CompiledIndex> {
        if (positives.isEmpty()) return listOf(requireNotNull(base).forSort(sort))
        return positives.map { index -> index.forSort(sort) }
    }

    fun secondaryIncludes(primaryKey: String): List<CompiledIndex> {
        return positives.filterNot { index -> index.key == primaryKey }.map(CompiledIndex::forNewest)
    }
}

internal data class CompiledIndex(
    val key: String,
    val term: SearchTerm?,
    val newestUrl: String,
    val sortedUrl: String,
    val url: String = sortedUrl,
    val inlineIds: IntArray? = null,
) {
    fun forNewest(): CompiledIndex = copy(url = newestUrl)
    fun forSort(sort: SortMode): CompiledIndex {
        return if (sort == SortMode.NEWEST || sort == SortMode.RANDOM) forNewest() else copy(url = sortedUrl)
    }
}

internal fun SortMode.toHitomiNozomiSort(): HitomiNozomiSort = when (this) {
    SortMode.NEWEST, SortMode.RANDOM -> HitomiNozomiSort.NEWEST
    SortMode.POPULAR -> HitomiNozomiSort.POPULAR_MONTH
    SortMode.TOP -> HitomiNozomiSort.POPULAR_YEAR
}

private fun Iterable<SearchTerm>.normalizedDistinctTerms(): List<SearchTerm> {
    return mapNotNull { term ->
        val value = term.value.trim().lowercase(Locale.ROOT).replace(WHITESPACE_PATTERN, " ")
        if (value.isBlank()) null else term.copy(
            value = value,
            sourceNamespace = term.sourceNamespace?.trim()?.lowercase(Locale.ROOT),
        )
    }.distinctBy { term -> term.termCacheKey() }
}

internal fun SearchTerm.termCacheKey(): String {
    val namespace = when {
        facet == SearchFacet.TAG && sourceNamespace == null -> HITOMI_TAG_NAMESPACE
        else -> sourceNamespace.orEmpty().lowercase(Locale.ROOT)
    }
    return "${facet.name}|$namespace|${value.trim().lowercase(Locale.ROOT)}"
}

private val WHITESPACE_PATTERN = Regex("\\s+")
