package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.PostId
import java.io.File

/** Compact process accounting; it retains neither decoded posts nor offline provenance references. */
internal class OfflineMediaCatalog {
    private data class Entry(val id: PostId?, val owners: Set<String>, val media: Int, val bytes: Long)
    private val entries = mutableMapOf<File, Entry>()
    private val ids = mutableMapOf<PostId, Int>()
    private val owners = mutableMapOf<String, Int>()
    private var mediaCount = 0
    private var bytes = 0L

    fun update(folder: File, id: PostId?, ownerIds: Set<String>, media: Int, size: Long) {
        entries.remove(folder)?.let { adjust(it, -1) }
        if (id != null || size > 0L) {
            val entry = Entry(id, ownerIds, media, size)
            entries[folder] = entry
            adjust(entry, 1)
        }
    }

    fun foldersOwnedBy(owner: String): List<File> = entries.filterValues { owner in it.owners }.keys.toList()

    fun snapshot() = OfflineMediaSnapshot(ids.size, mediaCount, bytes, owners.toMap(), ids.keys.toSet())

    fun clear() {
        entries.clear()
        ids.clear()
        owners.clear()
        mediaCount = 0
        bytes = 0L
    }

    private fun adjust(entry: Entry, direction: Int) {
        entry.id?.let { ids.adjustCount(it, direction) }
        entry.owners.forEach { owners.adjustCount(it, direction) }
        mediaCount += entry.media * direction
        bytes += entry.bytes * direction
    }

    private fun <T> MutableMap<T, Int>.adjustCount(key: T, direction: Int) {
        val count = (get(key) ?: 0) + direction
        if (count == 0) remove(key) else put(key, count)
    }
}
