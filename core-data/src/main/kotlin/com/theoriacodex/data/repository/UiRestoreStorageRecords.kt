package com.theoriacodex.data.repository

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The `state` object of `ui_restore_store_v2.json`: last tab, Library section, Search scroll positions, feed filters, and Viewer launch. */
@Serializable
internal data class UiRestoreStoreRecord(
    @SerialName("lastTab")
    val lastTab: String? = null,
    @SerialName("searchScrollStates")
    val searchScrollStates: Map<String, SearchScrollStateRecord> = emptyMap(),
    @SerialName("feedFabRestoreStates")
    val feedFabRestoreStates: Map<String, FeedFabRestoreState> = emptyMap(),
    @SerialName("viewerLaunchContext")
    val viewerLaunchContext: ViewerLaunchContextRecord? = null,
    @SerialName("librarySection")
    val librarySection: String? = null,
) {
    fun toMemoryState(): PersistedUiRestoreState {
        return PersistedUiRestoreState(
            lastTab = lastTab?.trim()?.takeIf(String::isNotEmpty),
            scrollStates = searchScrollStates.mapNotNull { (queryHash, record) ->
                queryHash.trim().takeIf(String::isNotEmpty)?.let { normalizedHash ->
                    normalizedHash to record.toDomain()
                }
            }.toMap(),
            feedFabRestoreStates = feedFabRestoreStates,
            viewerLaunchContext = viewerLaunchContext?.toDomain(),
            librarySection = librarySection,
        )
    }

    companion object {
        fun fromMemoryState(state: PersistedUiRestoreState): UiRestoreStoreRecord {
            return UiRestoreStoreRecord(
                lastTab = state.lastTab,
                searchScrollStates = state.scrollStates.mapValues { (_, value) ->
                    SearchScrollStateRecord.fromDomain(value)
                },
                feedFabRestoreStates = state.feedFabRestoreStates,
                viewerLaunchContext = state.viewerLaunchContext?.let(ViewerLaunchContextRecord::fromDomain),
                librarySection = state.librarySection,
            )
        }
    }
}

internal data class PersistedUiRestoreState(
    val lastTab: String?,
    val scrollStates: Map<String, SearchScrollState>,
    val feedFabRestoreStates: Map<String, FeedFabRestoreState>,
    val viewerLaunchContext: ViewerLaunchContext?,
    val librarySection: String? = null,
)

@Serializable
internal data class SearchScrollStateRecord(
    @SerialName("firstVisibleItemIndex")
    val firstVisibleItemIndex: Int = 0,
    @SerialName("firstVisibleItemOffsetPx")
    val firstVisibleItemOffsetPx: Int = 0,
) {
    fun toDomain(): SearchScrollState {
        return SearchScrollState(
            firstVisibleItemIndex = firstVisibleItemIndex.coerceAtLeast(0),
            firstVisibleItemOffsetPx = firstVisibleItemOffsetPx.coerceAtLeast(0),
        )
    }

    companion object {
        fun fromDomain(state: SearchScrollState): SearchScrollStateRecord {
            return SearchScrollStateRecord(
                firstVisibleItemIndex = state.firstVisibleItemIndex.coerceAtLeast(0),
                firstVisibleItemOffsetPx = state.firstVisibleItemOffsetPx.coerceAtLeast(0),
            )
        }
    }
}

@Serializable
internal data class ViewerLaunchContextRecord(
    @SerialName("queryHash") val queryHash: String = "",
    @SerialName("startIndex") val startIndex: Int = 0,
    @SerialName("streamSource") val streamSource: String = ViewerStreamSource.SEARCH.name,
    @SerialName("scrollOffsetHint") val scrollOffsetHint: Int = 0,
    @SerialName("recentsSection") val recentsSection: String? = null,
) {
    fun toDomain(): ViewerLaunchContext {
        val decodedStreamSource = runCatching { ViewerStreamSource.valueOf(streamSource) }
            .getOrDefault(ViewerStreamSource.SEARCH)
        return ViewerLaunchContext(
            queryHash = queryHash,
            startIndex = startIndex,
            streamSource = decodedStreamSource,
            scrollOffsetHint = scrollOffsetHint,
            recentsSection = decodeRestoredRecentsSection(recentsSection, decodedStreamSource, queryHash),
        )
    }

    companion object {
        fun fromDomain(context: ViewerLaunchContext): ViewerLaunchContextRecord {
            return ViewerLaunchContextRecord(
                queryHash = context.queryHash,
                startIndex = context.startIndex,
                streamSource = context.streamSource.name,
                scrollOffsetHint = context.scrollOffsetHint,
                recentsSection = context.recentsSection?.name,
            )
        }
    }
}
