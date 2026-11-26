package com.theoriacodex.app.media

class EpisodeClipWatch(private val graceMs: Long) {
    private val awaitingEnd = mutableMapOf<String, Long>()
    private val awaitingRetry = mutableSetOf<String>()

    fun expect(markers: List<String>, retried: Boolean, nowMs: Long) {
        if (retried) awaitingRetry += markers else markers.forEach { awaitingEnd[it] = nowMs + graceMs }
    }

    fun hasWork(batches: List<PostDownloadBatch>): Boolean =
        batches.activeClips().isNotEmpty() || awaitingEnd.keys.any(batches::holdsClip)

    fun finished(batches: List<PostDownloadBatch>, nowMs: Long): List<PostDownloadItem> {
        val ended = mutableListOf<PostDownloadItem>()
        for (item in batches.clipItems()) {
            val marker = item.request.marker
            if (item.active && awaitingRetry.remove(marker)) awaitingEnd[marker] = nowMs + graceMs
            if (!item.active && awaitingEnd.remove(marker) != null) ended += item
        }
        return ended
    }

    fun done(batches: List<PostDownloadBatch>, nowMs: Long): Boolean {
        awaitingEnd.entries.removeAll { (marker, deadline) -> deadline <= nowMs && !batches.holdsClip(marker) }
        return batches.activeClips().isEmpty() && awaitingEnd.keys.all(batches::holdsClip)
    }
}

private fun List<PostDownloadBatch>.clipItems(): List<PostDownloadItem> = flatMap { it.items }.filter { it.request.clip != null }

private fun List<PostDownloadBatch>.holdsClip(marker: String): Boolean = clipItems().any { it.request.marker == marker }
