package com.theoriacodex.data.repository

import com.google.gson.annotations.SerializedName

/** The `state` object of `ui_restore_store_v2.json`: last tab, Search scroll positions, feed filters, and Viewer launch. */
internal data class UiRestoreStoreRecord(
    @field:SerializedName("lastTab")
    val lastTab: String? = null,
    @field:SerializedName("searchScrollStates")
    val searchScrollStates: Map<String, SearchScrollStateRecord> = emptyMap(),
    @field:SerializedName("feedFabRestoreStates")
    val feedFabRestoreStates: Map<String, FeedFabRestoreState> = emptyMap(),
    @field:SerializedName("viewerLaunchContext")
    val viewerLaunchContext: ViewerLaunchContextRecord? = null,
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
            )
        }
    }
}

internal data class PersistedUiRestoreState(
    val lastTab: String?,
    val scrollStates: Map<String, SearchScrollState>,
    val feedFabRestoreStates: Map<String, FeedFabRestoreState>,
    val viewerLaunchContext: ViewerLaunchContext?,
)

internal data class SearchScrollStateRecord(
    @field:SerializedName("firstVisibleItemIndex")
    val firstVisibleItemIndex: Int = 0,
    @field:SerializedName("firstVisibleItemOffsetPx")
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

internal data class ViewerLaunchContextRecord(
    @field:SerializedName("queryHash") val queryHash: String = "",
    @field:SerializedName("startIndex") val startIndex: Int = 0,
    @field:SerializedName("streamSource") val streamSource: String = ViewerStreamSource.SEARCH.name,
    @field:SerializedName("scrollOffsetHint") val scrollOffsetHint: Int = 0,
    @field:SerializedName("recentsSection") val recentsSection: String? = null,
) {
    fun toDomain(): ViewerLaunchContext = decodeRestoredViewerLaunchContext(
        queryHash, startIndex, streamSource, scrollOffsetHint, recentsSection,
    )

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

internal fun decodeRestoredViewerLaunchContext(
    queryHash: String,
    startIndex: Int,
    streamSource: String,
    scrollOffsetHint: Int,
    recentsSection: String?,
): ViewerLaunchContext {
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
