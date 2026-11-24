package com.theoriacodex.data.storage

import kotlinx.serialization.KSerializer
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

inline fun <reified T> assertStoredFormat(vararg goldens: Pair<String, T>, json: Json = DurableJson, retiredKeys: Set<String> = emptySet()) =
    assertStoredFormat(serializer<T>(), *goldens, json = json, retiredKeys = retiredKeys)

fun <T> assertStoredFormat(
    serializer: KSerializer<T>,
    vararg goldens: Pair<String, T>,
    json: Json = DurableJson,
    retiredKeys: Set<String> = emptySet(),
) {
    require(goldens.isNotEmpty()) { "A stored format needs at least one golden" }
    val descriptor = serializer.descriptor
    val type = descriptor.serialName
    val held = mutableSetOf<String>()
    val undeclared = mutableSetOf<String>()
    goldens.forEachIndexed { index, (golden, expected) ->
        assertEquals("$type golden $index decodes differently", expected, json.decodeFromString(serializer, golden))
        assertEquals(
            "$type golden $index value changes in a round trip",
            expected,
            json.decodeFromString(serializer, json.encodeToString(serializer, expected)),
        )
        held += descriptor.heldPaths(json.parseToJsonElement(golden))
        undeclared += descriptor.undeclaredPaths(json.parseToJsonElement(golden))
    }
    val missing = descriptor.declaredPaths() - held
    assertTrue("$type goldens never hold $missing; add a golden that sets them", missing.isEmpty())
    val stale = undeclared - retiredKeys
    assertTrue("$type goldens hold $stale, which it does not declare; list a deliberately old golden's keys in retiredKeys", stale.isEmpty())
}

private fun SerialDescriptor.declaredPaths(prefix: String = "", visiting: Set<String> = emptySet()): Set<String> =
    when (kind) {
        StructureKind.CLASS -> {
            val type = serialName.removeSuffix("?")
            if (type in visiting) {
                emptySet()
            } else {
                (0 until elementsCount).flatMapTo(mutableSetOf()) { index ->
                    val path = child(prefix, getElementName(index))
                    getElementDescriptor(index).declaredPaths(path, visiting + type) + path
                }
            }
        }
        StructureKind.LIST -> getElementDescriptor(0).declaredPaths("$prefix[]", visiting)
        StructureKind.MAP -> getElementDescriptor(1).declaredPaths("$prefix{}", visiting)
        else -> emptySet()
    }

private fun SerialDescriptor.heldPaths(element: JsonElement, prefix: String = ""): Set<String> = when {
    kind == StructureKind.CLASS && element is JsonObject -> (0 until elementsCount).flatMapTo(mutableSetOf()) { index ->
        val path = child(prefix, getElementName(index))
        val value = element[getElementName(index)]
        if (value == null || value is JsonNull) emptySet() else getElementDescriptor(index).heldPaths(value, path) + path
    }
    kind == StructureKind.LIST && element is JsonArray ->
        element.flatMapTo(mutableSetOf()) { item -> getElementDescriptor(0).heldPaths(item, "$prefix[]") }
    kind == StructureKind.MAP && element is JsonObject ->
        element.values.flatMapTo(mutableSetOf()) { value -> getElementDescriptor(1).heldPaths(value, "$prefix{}") }
    else -> emptySet()
}

private fun SerialDescriptor.undeclaredPaths(element: JsonElement, prefix: String = ""): Set<String> = when {
    kind == StructureKind.CLASS && element is JsonObject -> element.entries.flatMapTo(mutableSetOf()) { (name, value) ->
        val index = getElementIndex(name)
        if (index == CompositeDecoder.UNKNOWN_NAME) setOf(child(prefix, name))
        else getElementDescriptor(index).undeclaredPaths(value, child(prefix, name))
    }
    kind == StructureKind.LIST && element is JsonArray ->
        element.flatMapTo(mutableSetOf()) { item -> getElementDescriptor(0).undeclaredPaths(item, "$prefix[]") }
    kind == StructureKind.MAP && element is JsonObject ->
        element.values.flatMapTo(mutableSetOf()) { value -> getElementDescriptor(1).undeclaredPaths(value, "$prefix{}") }
    else -> emptySet()
}

private fun child(prefix: String, name: String) = if (prefix.isEmpty()) name else "$prefix.$name"

