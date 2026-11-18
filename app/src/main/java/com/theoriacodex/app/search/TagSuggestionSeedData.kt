package com.theoriacodex.app.search

import android.content.Context
import com.theoriacodex.data.storage.DurableJson
import com.theoriacodex.data.storage.decodeEachOrSkip
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.SourceKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/** Starter suggestions from the bundled `tag_store.json`; an unreadable asset yields none rather than failing. */
internal fun loadSeedTagSuggestions(context: Context): Map<SourceKey, List<TagSuggestion>> {
    val seed = runCatching {
        val body = context.assets.open("tag_store.json").bufferedReader().use { it.readText() }
        DurableJson.decodeFromString(TagSeedFile.serializer(), body)
    }.getOrNull() ?: return emptyMap()
    return seed.sources.mapNotNull { (sourceName, entries) ->
        val source = SourceKey.parse(sourceName) ?: return@mapNotNull null
        val tags = (entries as? JsonArray).orEmpty().decodeEachOrSkip<TagSeedEntry>().mapNotNull { entry ->
            val text = entry.text?.trim().orEmpty()
            if (text.isBlank()) null else TagSuggestion(text = text, type = entry.type, count = entry.count ?: entry.postCount?.toInt())
        }
        if (tags.isEmpty()) null else source to tags
    }.toMap()
}

@Serializable
private class TagSeedFile(
    /** Each source's list, and each entry in it, decodes on its own, so one malformed value skips only itself. */
    @SerialName("sources")
    val sources: Map<String, JsonElement> = emptyMap(),
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
