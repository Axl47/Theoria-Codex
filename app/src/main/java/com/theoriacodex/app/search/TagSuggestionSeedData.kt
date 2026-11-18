package com.theoriacodex.app.search

import android.content.Context
import com.theoriacodex.data.storage.DurableJson
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.SourceKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Starter suggestions from the bundled `tag_store.json`; an unreadable asset yields none rather than failing. */
internal fun loadSeedTagSuggestions(context: Context): Map<SourceKey, List<TagSuggestion>> {
    val seed = runCatching {
        val body = context.assets.open("tag_store.json").bufferedReader().use { it.readText() }
        DurableJson.decodeFromString(TagSeedFile.serializer(), body)
    }.getOrNull() ?: return emptyMap()
    return seed.sources.mapNotNull { (sourceName, entries) ->
        val source = SourceKey.parse(sourceName) ?: return@mapNotNull null
        val tags = entries.mapNotNull { entry ->
            val text = entry.text?.trim().orEmpty()
            if (text.isBlank()) null else TagSuggestion(text = text, type = entry.type, count = entry.count ?: entry.postCount?.toInt())
        }
        if (tags.isEmpty()) null else source to tags
    }.toMap()
}

@Serializable
private class TagSeedFile(
    @SerialName("sources")
    val sources: Map<String, List<TagSeedEntry>> = emptyMap(),
)

@Serializable
private class TagSeedEntry(
    @SerialName("text")
    val text: String? = null,
    @SerialName("type")
    val type: String? = null,
    @SerialName("count")
    val count: Int? = null,
    @SerialName("postCount")
    val postCount: Long? = null,
)
