package com.theoriacodex.data.android.room

import com.theoriacodex.data.storage.DurableJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/** `liked_posts.tags_json`: a JSON array of tag strings, written by Likes and by backup restores alike. */
internal object LikedTagsCodec {
    private val serializer = ListSerializer(String.serializer())

    fun encode(tags: List<String>): String = DurableJson.encodeToString(serializer, tags)

    /** Throws [kotlinx.serialization.SerializationException] when the column is not an array of strings. */
    fun decode(json: String): List<String> = DurableJson.decodeFromString(serializer, json)
}
