package com.theoriacodex.sources.common

/**
 * Thread-safe least-recently-used cache holding at most [maxEntries] entries; reads count as use.
 * For small metadata caches whose entries are cheap and similar in size (Hitomi's payload caches
 * budget bytes instead, see `HitomiByteBudgetCache`).
 */
internal class BoundedLruCache<Key, Value>(private val maxEntries: Int) {
    private val entries = LinkedHashMap<Key, Value>(16, 0.75f, true)

    init {
        require(maxEntries > 0) { "Cache capacity must be positive" }
    }

    operator fun get(key: Key): Value? = synchronized(entries) { entries[key] }

    /** Replaces [key]'s value with `transform(current)` atomically, evicting the eldest entry past capacity. */
    fun update(key: Key, transform: (Value?) -> Value) {
        synchronized(entries) {
            entries[key] = transform(entries[key])
            if (entries.size > maxEntries) entries.remove(entries.keys.first())
        }
    }
}
