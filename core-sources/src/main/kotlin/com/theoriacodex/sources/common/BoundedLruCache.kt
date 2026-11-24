package com.theoriacodex.sources.common

internal class BoundedLruCache<Key, Value>(private val maxEntries: Int) {
    private val entries = LinkedHashMap<Key, Value>(16, 0.75f, true)

    init {
        require(maxEntries > 0) { "Cache capacity must be positive" }
    }

    operator fun get(key: Key): Value? = synchronized(entries) { entries[key] }

    fun update(key: Key, transform: (Value?) -> Value) {
        synchronized(entries) {
            entries[key] = transform(entries[key])
            if (entries.size > maxEntries) entries.remove(entries.keys.first())
        }
    }
}
