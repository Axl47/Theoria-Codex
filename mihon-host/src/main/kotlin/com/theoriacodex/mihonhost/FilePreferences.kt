package com.theoriacodex.mihonhost

import android.content.SharedPreferences
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.WeakHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * An extension's preferences, kept as one JSON file. Extensions store what the user typed into their settings, which
 * for a self-hosted server is its address, username and password, and Android backs up every file in `shared_prefs`
 * unless a rule names it. The host therefore keeps these files in a directory Android never backs up or transfers
 * (see [MihonHost]), which the platform's own `SharedPreferences` cannot be pointed at.
 *
 * Differs from the platform's in two ways no extension depends on: `apply` writes before it returns, and listeners
 * are called on the thread that made the change.
 */
internal class FilePreferences(private val file: File) : SharedPreferences {
    private val listeners = WeakHashMap<SharedPreferences.OnSharedPreferenceChangeListener, Unit>()
    private var values: Map<String, Any> = read()

    override fun getAll(): Map<String, *> = synchronized(this) { values.toMap() }

    override fun getString(key: String?, defValue: String?): String? = value(key) ?: defValue

    override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? = value<Set<String>>(key)?.toSet() ?: defValues

    override fun getInt(key: String?, defValue: Int): Int = value(key) ?: defValue

    override fun getLong(key: String?, defValue: Long): Long = value(key) ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float = value(key) ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean = value(key) ?: defValue

    override fun contains(key: String?): Boolean = synchronized(this) { key in values }

    override fun edit(): SharedPreferences.Editor = Edit()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        synchronized(this) { listeners[listener] = Unit }
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        synchronized(this) { listeners.remove(listener) }
    }

    /** As the platform does, a value stored as another type than the one asked for is the caller's error. */
    @Suppress("UNCHECKED_CAST")
    private fun <T> value(key: String?): T? = synchronized(this) { values[key] } as T?

    private inner class Edit : SharedPreferences.Editor {
        // A null value removes the key, as putString(key, null) does on the platform.
        private val changes = LinkedHashMap<String, Any?>()
        private var clear = false

        override fun putString(key: String, value: String?) = put(key, value)

        override fun putStringSet(key: String, values: Set<String>?) = put(key, values?.toSet())

        override fun putInt(key: String, value: Int) = put(key, value)

        override fun putLong(key: String, value: Long) = put(key, value)

        override fun putFloat(key: String, value: Float) = put(key, value)

        override fun putBoolean(key: String, value: Boolean) = put(key, value)

        override fun remove(key: String) = put(key, null)

        override fun clear() = apply { clear = true }

        override fun apply() {
            commit()
        }

        override fun commit(): Boolean {
            val (changed, notified) = synchronized(this@FilePreferences) {
                val before = values
                val after = (if (clear) emptyMap() else before).toMutableMap()
                changes.forEach { (key, value) -> if (value == null) after.remove(key) else after[key] = value }
                val changed = (before.keys + after.keys).filter { before[it] != after[it] }
                if (changed.isEmpty()) return true
                runCatching { write(after) }.onFailure { return false }
                values = after
                changed to listeners.keys.toList()
            }
            notified.forEach { listener -> changed.forEach { listener.onSharedPreferenceChanged(this@FilePreferences, it) } }
            return true
        }

        private fun put(key: String, value: Any?) = apply { changes[key] = value }
    }

    private fun read(): Map<String, Any> = runCatching {
        Json.parseToJsonElement(file.readText()).jsonObject.mapValues { (_, tagged) -> decode(tagged.jsonObject) }
    }.getOrDefault(emptyMap())

    private fun write(values: Map<String, Any>) {
        file.parentFile?.mkdirs()
        val pending = File(file.path + ".new")
        pending.writeText(JsonObject(values.mapValues { (_, value) -> encode(value) }).toString())
        Files.move(pending.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    /** Writes every value of [from], which another store held until now. */
    fun takeAll(from: Map<String, *>) {
        val editor = edit()
        from.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstanceTo(LinkedHashSet()))
            }
        }
        editor.commit()
    }

    private companion object {
        // Each value is written under a one-letter name for its type, which JSON alone would not keep apart.
        fun encode(value: Any): JsonElement = when (value) {
            is String -> JsonObject(mapOf("s" to JsonPrimitive(value)))
            is Boolean -> JsonObject(mapOf("b" to JsonPrimitive(value)))
            is Int -> JsonObject(mapOf("i" to JsonPrimitive(value)))
            is Long -> JsonObject(mapOf("l" to JsonPrimitive(value)))
            is Float -> JsonObject(mapOf("f" to JsonPrimitive(value)))
            is Set<*> -> JsonObject(mapOf("t" to JsonArray(value.map { JsonPrimitive(it as String) })))
            else -> error("Not a preference value: $value")
        }

        fun decode(tagged: JsonObject): Any {
            val (type, value) = tagged.entries.single()
            return when (type) {
                "s" -> value.jsonPrimitive.content
                "b" -> value.jsonPrimitive.boolean
                "i" -> value.jsonPrimitive.int
                "l" -> value.jsonPrimitive.long
                "f" -> value.jsonPrimitive.float
                "t" -> value.jsonArray.mapTo(LinkedHashSet()) { it.jsonPrimitive.content }
                else -> error("Unknown preference type $type")
            }
        }
    }
}
