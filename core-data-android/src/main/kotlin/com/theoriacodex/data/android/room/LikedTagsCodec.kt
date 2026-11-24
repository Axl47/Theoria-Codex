package com.theoriacodex.data.android.room

import com.theoriacodex.data.storage.DurableJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

internal object LikedTagsCodec {
    private val serializer = ListSerializer(String.serializer())

    fun encode(tags: List<String>): String = DurableJson.encodeToString(serializer, tags)

    fun decode(json: String): List<String> = DurableJson.decodeFromString(serializer, json)
}
