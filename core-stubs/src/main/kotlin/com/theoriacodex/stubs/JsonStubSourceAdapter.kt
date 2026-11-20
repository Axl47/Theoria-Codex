package com.theoriacodex.stubs

import com.theoriacodex.domain.adapter.Page
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceCapabilities
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.delay

class JsonStubSourceAdapter(
    override val sourceKey: SourceKey,
    private val fixtureLoader: StubFixtureLoader,
    private val runtime: StubRuntime,
    override val capabilities: SourceCapabilities = defaultCapabilities(),
) : SourceAdapter {
    override suspend fun search(query: Query, pageToken: String?): Page<Post> {
        applyScenarioDelayAndFailure(failOnSearch = true)

        if (runtime.preset == StubScenarioPreset.EMPTY_RESULTS) {
            return Page(items = emptyList(), nextPageToken = null)
        }

        val fixture = fixtureLoader.loadSearchPage(sourceKey, pageToken)
        return Page(
            items = fixture.items.map { it.toDomain(sourceKey) },
            nextPageToken = fixture.nextPageToken,
        )
    }

    override suspend fun trendingTags(limit: Int): List<TagSuggestion> {
        applyScenarioDelayAndFailure(failOnSearch = false)

        if (runtime.preset == StubScenarioPreset.EMPTY_RESULTS) {
            return emptyList()
        }

        return fixtureLoader
            .loadTrending(sourceKey)
            .items
            .map { it.toDomain() }
            .take(limit)
    }

    override suspend fun autocompleteTags(prefix: String, limit: Int): List<TagSuggestion> {
        applyScenarioDelayAndFailure(failOnSearch = false)
        if (prefix.isBlank()) return emptyList()

        return fixtureLoader
            .loadTrending(sourceKey)
            .items
            .map { it.toDomain() }
            .filter { it.text.contains(prefix, ignoreCase = true) }
            .take(limit)
    }

    override suspend fun resolvePost(id: PostId): Post? {
        if (id.source != sourceKey) return null

        val firstPage = fixtureLoader.loadSearchPage(sourceKey, pageToken = null)
        val secondPage = fixtureLoader.loadSearchPage(sourceKey, pageToken = "page_2")

        return (firstPage.items + secondPage.items)
            .firstOrNull { it.sourcePostId == id.sourcePostId }
            ?.toDomain(sourceKey)
    }

    private suspend fun applyScenarioDelayAndFailure(failOnSearch: Boolean) {
        val preset = runtime.preset

        if (preset == StubScenarioPreset.SLOW_NETWORK) {
            delay(900)
        }

        if (preset == StubScenarioPreset.PARTIAL_FAILURE) {
            val config = fixtureLoader
                .loadFailureScenario()
                .sources[sourceKey.id]
                ?: return
            if (config.delayMs > 0) {
                delay(config.delayMs)
            }
            if (failOnSearch && config.failSearch) {
                throw SourceAdapterException(
                    reason = SourceFailureReason.NETWORK,
                    message = "Stub search failure for $sourceKey",
                )
            }
            if (!failOnSearch && config.failTrending) {
                throw SourceAdapterException(
                    reason = SourceFailureReason.NETWORK,
                    message = "Stub trending failure for $sourceKey",
                )
            }
        }
    }

    companion object {
        fun defaultCapabilities(): SourceCapabilities {
            return SourceCapabilities(
                supportsSortNewest = true,
                supportsSortPopular = true,
                supportsSortTop = true,
                supportsSortRandom = true,
                supportsExcludeTagsServerSide = true,
            )
        }
    }
}
