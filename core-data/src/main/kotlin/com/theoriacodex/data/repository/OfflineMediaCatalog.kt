package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.PostId
import java.io.File

internal class OfflineMediaCatalog {
    private data class Entry(val id: PostId?, val owners: Set<String>, val media: Int, val bytes: Long, val leadPages: List<ImageRef>)
    private val entries = mutableMapOf<File, Entry>()
    private val ids = mutableMapOf<PostId, Int>()
    private val ownerFolders = mutableMapOf<String, Int>()
    private val ownerPosts = mutableMapOf<String, MutableSet<PostId>>()
    private var mediaCount = 0
    private var bytes = 0L

    fun update(folder: File, id: PostId?, ownerIds: Set<String>, media: Int, size: Long, leadPages: List<ImageRef> = emptyList()) {
        entries.remove(folder)?.let { adjust(it, -1) }
        if (id != null || size > 0L) {
            val entry = Entry(id, ownerIds, media, size, leadPages)
            entries[folder] = entry
            adjust(entry, 1)
        }
    }

    fun foldersOwnedBy(owner: String): List<File> = entries.filterValues { owner in it.owners }.keys.toList()

    fun snapshot() = OfflineMediaSnapshot(
        ids.size, mediaCount, bytes, ownerFolders.keys.associateWith { ownerPosts[it]?.toSet().orEmpty() }, ids.keys.toSet(),
        entries.values.mapNotNull { entry -> entry.id?.takeIf { entry.leadPages.isNotEmpty() }?.let { it to entry.leadPages } }.toMap(),
    )

    fun clear() {
        entries.clear()
        ids.clear()
        ownerFolders.clear()
        ownerPosts.clear()
        mediaCount = 0
        bytes = 0L
    }

    private fun adjust(entry: Entry, direction: Int) {
        entry.id?.let { ids.adjustCount(it, direction) }
        entry.owners.forEach { owner ->
            ownerFolders.adjustCount(owner, direction)
            if (entry.id != null) {
                val posts = ownerPosts.getOrPut(owner, ::mutableSetOf)
                if (direction > 0) posts += entry.id else posts -= entry.id
                if (posts.isEmpty()) ownerPosts.remove(owner)
            }
        }
        mediaCount += entry.media * direction
        bytes += entry.bytes * direction
    }

    private fun <T> MutableMap<T, Int>.adjustCount(key: T, direction: Int) {
        val count = (get(key) ?: 0) + direction
        if (count == 0) remove(key) else put(key, count)
    }
}
