package com.theoriacodex.data.repository

import com.google.gson.annotations.SerializedName

internal data class UiRestoreStoreFile(
    @field:SerializedName("lastTab")
    val lastTab: String? = null,
    @field:SerializedName("searchScrollStates")
    val searchScrollStates: Map<String, SearchScrollStateRecord> = emptyMap(),
    @field:SerializedName("settingsSectionExpansion")
    val settingsSectionExpansion: Map<String, Boolean> = emptyMap(),
    @field:SerializedName("feedFabRestoreStates")
    val feedFabRestoreStates: Map<String, FeedFabRestoreState> = emptyMap(),
    @field:SerializedName("viewerLaunchContext")
    val viewerLaunchContext: ViewerLaunchContextRecord? = null,
)

internal data class UiRestoreMemoryState(
    val lastTab: String?,
    val scrollStates: Map<String, SearchScrollState>,
    val settingsSectionExpansion: Map<String, Boolean>,
    val feedFabRestoreStates: Map<String, FeedFabRestoreState>,
    val viewerLaunchContext: ViewerLaunchContext?,
)

internal data class SearchScrollStateRecord(
    @field:SerializedName("firstVisibleItemIndex")
    val firstVisibleItemIndex: Int = 0,
    @field:SerializedName("firstVisibleItemOffsetPx")
    val firstVisibleItemOffsetPx: Int = 0,
)

internal data class ViewerLaunchContextRecord(
    @field:SerializedName("streamSource") val streamSource: String = ViewerStreamSource.SEARCH.name,
    @field:SerializedName("queryHash") val queryHash: String = "",
    @field:SerializedName("recentsSection") val recentsSection: String? = null,
    @field:SerializedName("startIndex") val startIndex: Int = 0,
    @field:SerializedName("scrollOffsetHint") val scrollOffsetHint: Int = 0,
) {
    fun toDomain(): ViewerLaunchContext = decodeRestoredViewerLaunchContext(
        queryHash, startIndex, streamSource, scrollOffsetHint, recentsSection,
    )

    companion object {
        fun fromDomain(context: ViewerLaunchContext) = ViewerLaunchContextRecord(
            streamSource = context.streamSource.name,
            queryHash = context.queryHash,
            recentsSection = context.recentsSection?.name,
            startIndex = context.startIndex,
            scrollOffsetHint = context.scrollOffsetHint,
        )
    }
}
