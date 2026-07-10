package com.theoriacodex.app.search

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.SourceKey

internal fun loadSeedTagSuggestions(context: Context): Map<SourceKey, List<TagSuggestion>> {
    val body = runCatching {
        context.assets.open("tag_store.json").bufferedReader().use { it.readText() }
    }.getOrNull() ?: return emptyMap()
    val root = runCatching { Gson().fromJson(body, JsonObject::class.java) }.getOrNull()
        ?: return emptyMap()
    val sources = root.getAsJsonObject("sources") ?: return emptyMap()
    return sources.entrySet().mapNotNull outer@{ (sourceName, value) ->
        val source = runCatching { SourceKey.valueOf(sourceName) }.getOrNull() ?: return@outer null
        val tags = value.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull inner@{ element ->
                val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@inner null
                val text = obj.get("text")
                    ?.takeUnless { it.isJsonNull }
                    ?.asString
                    ?.trim()
                    .orEmpty()
                if (text.isBlank()) return@inner null
                val postCount = obj.get("postCount")?.takeIf { it.isJsonPrimitive }?.asLong
                TagSuggestion(
                    text = text,
                    type = obj.get("type")
                        ?.takeUnless { it.isJsonNull }
                        ?.asString,
                    count = obj.get("count")
                        ?.takeUnless { it.isJsonNull }
                        ?.asInt ?: postCount?.toInt(),
                )
            }
            .orEmpty()
        if (tags.isEmpty()) null else source to tags
    }.toMap()
}
