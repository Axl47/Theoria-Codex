package com.theoriacodex.extensionhost

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
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

internal class FilePreferences(
    private val file: File,
    private val report: (Throwable) -> Unit = { Log.w("ExtensionHost", "An extension's preference listener failed", it) },
) : SharedPreferences {
    private val lock = Any()

    private var unwritten = false
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

    @Suppress("UNCHECKED_CAST")
    private fun <T> value(key: String?): T? = synchronized(lock) { values[key] } as T?

    private inner class Edit : SharedPreferences.Editor {
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

        override fun commit(): Boolean {
            val written: Boolean
            val changed: List<String>
            val notified: List<SharedPreferences.OnSharedPreferenceChangeListener>
            synchronized(lock) {
                val before = values
                val after = (if (clear) emptyMap() else before).toMutableMap()
                changes.forEach { (key, value) -> if (value == null) after.remove(key) else after[key] = value }
                changes.clear()
                clear = false
                changed = (before.keys + after.keys).filter { before[it] != after[it] }
                if (changed.isEmpty() && !unwritten) return true
                values = after
                written = runCatching { write(after) }.isSuccess
                unwritten = !written
                notified = listeners.keys.toList()
            }
            if (notified.isNotEmpty() && changed.isNotEmpty()) mainThread.post { announce(notified, changed) }
            return written
        }

        private fun put(key: String, value: Any?) = apply { synchronized(lock) { changes[key] = value } }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun announce(notified: List<SharedPreferences.OnSharedPreferenceChangeListener>, changed: List<String>) {
        notified.forEach { listener ->
            try {
                changed.forEach { listener.onSharedPreferenceChanged(this, it) }
            } catch (failure: Throwable) {
                if (failure.endsTheProcess()) throw failure
                report(failure)
            }
        }
    }

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
