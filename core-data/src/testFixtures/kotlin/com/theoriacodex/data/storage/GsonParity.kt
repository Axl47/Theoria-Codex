package com.theoriacodex.data.storage

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import java.lang.reflect.Type
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * Asserts that [T]'s kotlinx serializer under [DurableJson] matches the production `Gson()` it replaces.
 *
 * For each sample: both libraries emit the same key paths, Gson's output decodes through kotlinx to the sample,
 * and kotlinx's output decodes through Gson to the sample. Together the samples must emit every property, at any
 * depth, so a renamed key cannot hide behind a value that is always null. For each golden file (bytes an older
 * build wrote): both libraries decode it to equal values. Failures name the type and the differing keys.
 */
inline fun <reified T : Any> assertGsonParity(
    samples: List<T>,
    goldenJson: List<String> = emptyList(),
    serializer: KSerializer<T> = serializer(),
) = assertGsonParity(serializer, object : TypeToken<T>() {}.type, samples, goldenJson)

fun <T : Any> assertGsonParity(serializer: KSerializer<T>, gsonType: Type, samples: List<T>, goldenJson: List<String>) {
    require(samples.isNotEmpty()) { "Parity needs at least one sample" }
    val gson = Gson()
    val type = serializer.descriptor.serialName
    val emitted = mutableSetOf<String>()
    samples.forEachIndexed { index, sample ->
        val gsonJson = gson.toJson(sample, gsonType)
        val kotlinxJson = DurableJson.encodeToString(serializer, sample)
        val gsonKeys = keyPaths(JsonParser.parseString(gsonJson))
        val kotlinxKeys = keyPaths(JsonParser.parseString(kotlinxJson))
        assertEquals(
            "$type sample $index keys differ: only Gson ${gsonKeys - kotlinxKeys}, only kotlinx ${kotlinxKeys - gsonKeys}",
            gsonKeys,
            kotlinxKeys,
        )
        assertEquals("$type sample $index: Gson output decoded by kotlinx", sample, DurableJson.decodeFromString(serializer, gsonJson))
        assertEquals("$type sample $index: kotlinx output decoded by Gson", sample, gson.fromJson<T>(kotlinxJson, gsonType))
        emitted += kotlinxKeys
    }
    val unexercised = serializer.descriptor.propertyPaths() - emitted
    assertTrue("$type samples never emit $unexercised; give those properties non-null values", unexercised.isEmpty())
    goldenJson.forEachIndexed { index, golden ->
        assertEquals(
            "$type golden $index decodes differently",
            gson.fromJson<T>(golden, gsonType),
            DurableJson.decodeFromString(serializer, golden),
        )
    }
}

/** Every object key in [element] as a dotted path; array items share their array's path with a `[]` suffix. */
private fun keyPaths(element: JsonElement, prefix: String = ""): Set<String> = when {
    element.isJsonObject -> element.asJsonObject.entrySet().flatMapTo(mutableSetOf()) { (key, value) ->
        val path = if (prefix.isEmpty()) key else "$prefix.$key"
        keyPaths(value, path) + path
    }
    element.isJsonArray -> element.asJsonArray.flatMapTo(mutableSetOf()) { item -> keyPaths(item, "$prefix[]") }
    else -> emptySet()
}

/**
 * Property paths a class descriptor declares, in [keyPaths]' format. Map contents have dynamic keys and are not
 * descended; a type already on the current path (recursion) stops the walk.
 */
private fun SerialDescriptor.propertyPaths(prefix: String = "", visiting: Set<String> = emptySet()): Set<String> {
    val type = serialName.removeSuffix("?")
    if (type in visiting) return emptySet()
    val path = visiting + type
    return when (kind) {
        StructureKind.CLASS -> (0 until elementsCount).flatMapTo(mutableSetOf()) { index ->
            val name = if (prefix.isEmpty()) getElementName(index) else "$prefix.${getElementName(index)}"
            getElementDescriptor(index).propertyPaths(name, path) + name
        }
        StructureKind.LIST -> getElementDescriptor(0).propertyPaths("$prefix[]", path)
        else -> emptySet()
    }
}
