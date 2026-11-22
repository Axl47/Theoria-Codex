package com.theoriacodex.mihonhost

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
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
 * As on the platform, the values in memory are what readers see: an edit is visible as soon as it is applied, also
 * when its write fails, and listeners are called on the main thread. Unlike the platform, `apply` writes the file
 * before it returns, on the caller's thread: the files are a few hundred bytes and extensions change them off the
 * main thread, so a write queue would add more than it saves.
 */
internal class FilePreferences(private val file: File) : SharedPreferences {
    private val lock = Any()
    private val listeners = WeakHashMap<SharedPreferences.OnSharedPreferenceChangeListener, Unit>()
    private var values: Map<String, Any> = read()

    override fun getAll(): Map<String, *> = synchronized(lock) { values.toMap() }

    override fun getString(key: String?, defValue: String?): String? = value(key) ?: defValue

    override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? = value<Set<String>>(key)?.toSet() ?: defValues

    override fun getInt(key: String?, defValue: Int): Int = value(key) ?: defValue

    override fun getLong(key: String?, defValue: Long): Long = value(key) ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float = value(key) ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean = value(key) ?: defValue

    override fun contains(key: String?): Boolean = synchronized(lock) { key in values }

    override fun edit(): SharedPreferences.Editor = Edit()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        synchronized(lock) { listeners[listener] = Unit }
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        synchronized(lock) { listeners.remove(listener) }
    }

    /** As the platform does, a value stored as another type than the one asked for is the caller's error. */
    @Suppress("UNCHECKED_CAST")
    private fun <T> value(key: String?): T? = synchronized(lock) { values[key] } as T?

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

        override fun clear() = apply { synchronized(lock) { clear = true } }

        override fun apply() {
            commit()
        }

        /** Whether the file now holds the change; the values in memory hold it either way. */
        override fun commit(): Boolean {
            val written: Boolean
            val changed: List<String>
            val notified: List<SharedPreferences.OnSharedPreferenceChangeListener>
            synchronized(lock) {
                val before = values
                val after = (if (clear) emptyMap() else before).toMutableMap()
                changes.forEach { (key, value) -> if (value == null) after.remove(key) else after[key] = value }
                // Drained: applying this editor again must not replay what it already applied.
                changes.clear()
                clear = false
                changed = (before.keys + after.keys).filter { before[it] != after[it] }
                if (changed.isEmpty()) return true
                values = after
                written = runCatching { write(after) }.isSuccess
                notified = listeners.keys.toList()
            }
            if (notified.isNotEmpty()) {
                mainThread.post { notified.forEach { listener -> changed.forEach { listener.onSharedPreferenceChanged(this@FilePreferences, it) } } }
            }
            return written
        }

        private fun put(key: String, value: Any?) = apply { synchronized(lock) { changes[key] = value } }
    }

    /**
     * What the file holds. An entry that cannot be read is left out and the rest kept. A file that is not a JSON
     * object at all is set aside as `.corrupt` rather than left for the next write to replace unseen.
     */
    private fun read(): Map<String, Any> {
        if (!file.exists()) return emptyMap()
        val entries = runCatching { Json.parseToJsonElement(file.readText()).jsonObject }.getOrElse {
            runCatching { Files.move(file.toPath(), File(file.path + ".corrupt").toPath(), StandardCopyOption.REPLACE_EXISTING) }
            return emptyMap()
        }
        return entries.mapNotNull { (key, tagged) -> runCatching { key to decode(tagged.jsonObject) }.getOrNull() }.toMap()
    }

    private fun write(values: Map<String, Any>) {
        file.parentFile?.mkdirs()
        val pending = File(file.path + ".new")
        pending.writeText(JsonObject(values.mapValues { (_, value) -> encode(value) }).toString())
        Files.move(pending.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private companion object {
        val mainThread by lazy { Handler(Looper.getMainLooper()) }

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
