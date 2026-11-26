package com.theoriacodex.app.viewer

import com.theoriacodex.domain.model.MediaStream
import com.theoriacodex.domain.model.MediaTrack
import org.junit.Assert.assertEquals
import org.junit.Test

class EpisodeClipTracksTest {
    @Test
    fun `refreshed links keep the chosen tracks by label and repeat, or drop a track that is gone (ADR 0223)`() {
        val before = MediaStream("https://a.test/e.m3u8", "Main",
            subtitles = listOf(track("English"), track("English"), track("Signs")), audioTracks = listOf(track("Japanese"), track("English")))
        val after = before.copy(subtitles = listOf(track("Signs"), track("English"), track("English")), audioTracks = listOf(track("English")))

        assertEquals(
            EpisodeClipTracks(subtitles = true, subtitleIndex = 2, audioIndex = null),
            EpisodeClipTracks(subtitles = true, subtitleIndex = 1, audioIndex = 0).movedTo(before, after),
        )
    }

    private fun track(label: String) = MediaTrack("https://a.test/${label.lowercase()}", label)
}
