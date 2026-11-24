# 0116. A playback request fixes the stream type, headers, tracks and subtitle loading from what the media offers

Status: Accepted

## Context

Series episodes come from extensions with HLS and DASH manifests, separate audio and subtitle files at addresses that name no format, and expiring links with their own headers.

## Decision

- `streamType` decides by the request's mime when present, else by the path extension (`.m3u8`, `.mpd`; query ignored). `streamMimeType` normalizes HLS spellings (`application/vnd.apple.mpegurl`, `audio/mpegurl`) to the `application/x-mpegURL` Media3 recognises. Formats the app does not play as such (SmoothStreaming, RTSP) are progressive files, so they fail as an ordinary player error instead of a missing media source. Separate audio takes its own extension's type, else the main stream's, since an extension's HLS episode often names its audio playlist with no extension (`separateAudioType`).
- `StreamMediaSourceFactory` merges children in the order video, audio, subtitles; Media3 prefixes each track group id with its child's index, which is how `separateAudioIndex` and `playbackTracks` label separate audio. Every child reads through the request's data source factory so it carries the stream's headers.
- Subtitles are side-loaded and loaded only once selected; a failed one ends its track, not the video. Media3's own side loading (`MediaItem.subtitleConfigurations`) downloads every subtitle file before playback starts and one dead link fails the player. `SideLoadedSubtitleRenderersFactory` supplies a text renderer that decodes whole files as they arrive (Media3's fails playback on undecoded side-loaded files). `SniffingSubtitleDecoder` decodes in the format the first bytes show (WebVTT header, SSA/ASS, TTML, SRT cue; UTF-8 or UTF-16 by byte order mark), else the format's own name, since extension streams serve subtitles at addresses with no or the wrong extension; the extension is only a fallback. A file it cannot place or parse shows no subtitles and playback goes on. The decoder runs on the renderer's decoder thread once the file arrives, and again after a seek.
- `Player.clearTrackChoices` undoes subtitle and audio choices so the player's next video starts on Media3's own choice. `selectSubtitle` and `selectAudio` ignore ids the video does not offer.
- `viewerPlayback` is the one place the Viewer's player and prefetcher get a stream from, so both name the same location, headers and cache identity. A series episode's `streams` play the stream labelled `streamLabel`, else the first (its source's preferred one), outside the shared cache, with exactly that stream's headers: the extension host already gave a stream without headers of its own its source's, and the source's here would be a custom site's placeholders. A label that vanishes after links are refetched falls back to the first (`viewerStream`).

## Consequences

One request describes exactly one playable stream. Adding a stream format means extending `streamType` and the source factory together.

## Applies to

- `media-kit/src/main/kotlin/com/theoriacodex/app/viewer/VideoMediaSources.kt`: `streamMimeType`, `streamType`, `StreamMediaSourceFactory`
- `media-kit/src/main/kotlin/com/theoriacodex/app/viewer/SideLoadedSubtitles.kt`: `subtitleMimeType`, `SideLoadedSubtitleRenderersFactory`, `SniffingSubtitleDecoder`
- `media-kit/src/main/kotlin/com/theoriacodex/app/viewer/PlaybackTracks.kt`: `playbackTracks`, `clearTrackChoices`, `separateAudioIndex`
- `media-kit/src/main/kotlin/com/theoriacodex/app/viewer/VideoPlaybackRequest.kt`: `viewerPlayback`, `viewerStream`
