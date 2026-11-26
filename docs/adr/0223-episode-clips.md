# 0223. Episode clips are encoded download jobs that keep only their range

Status: Accepted

## Context

Watching a series episode, the owner wants to save up to about a minute of it, with the subtitles on screen, as a
small file to share. Episode streams are usually HLS, sometimes AES-128 encrypted, with separate audio and subtitle
files and links that expire after minutes. The app already has one durable download queue (ADR 0113) and one way to
read these streams with the right headers (`VideoPlaybackInfrastructure.bind`). A clip has to be cut exactly, and
burning in subtitles means decoding anyway, so it is always re-encoded. Android freezes a backgrounded app's
process, so an encode that only lives in the app's scope stalls once the user leaves it.

## Decision

- Selection: "Clip" in the episode's ⋮ menu puts the Viewer in clip mode (`ViewerControlsState.clip`). The bottom
  bar becomes a two-minute strip centred on the selection with a handle at each end, starting with the 30 seconds
  after the playhead. A selection is one second to one minute inside the episode (`EpisodeClipRange`). The player
  loops it, even when the selection reaches the episode's end, so autoplay never moves on while clipping, and the
  top bar holds only ✕, "Clip · length" and Save. Watch progress is not recorded while clipping, so previewing a
  selection near the end does not mark the episode watched. Leaving the episode, entering picture-in-picture or
  pressing Back ends clip mode, and the player returns to where it was; if the player is released mid-clip, that
  position, not the clip's, is saved as the resume point. A handle drag keeps the handle nearest
  the first touch.
- Save emits `ViewerEffect.ClipEpisode`, and the route submits `PostDownloads.submitClip` at once, with the stream
  that is playing and the subtitle and audio tracks selected in the player (`EpisodeClipTracks`), so the job and
  its foreground service start while the app is still in front. The request carries an in-memory refresh that the
  job runs each time it prepares, retries included; it fetches new links only if they are old by then, so closing
  the Viewer cannot lose the clip and a clip queued behind another does not encode from expired links. The clip then
  shows in the download log like any download, with a determinate "Clipping" bar while it encodes.
- The queue runs a clip as `PreparedPostDownload.Encode` under the status `ENCODING`. Only one clip holds a worker
  at a time; the others stay queued, so they never take a slot from ordinary downloads. The job
  file stores the clip's range only, never URLs or headers, matching ADR 0113. A clip restored after the process
  died fails with "This clip was interrupted" and is not retryable, since its links are gone. A damaged clip row is
  dropped alone instead of failing the whole queue. `ENCODING` is never written: it is saved as `PREPARING`, which
  restores the same way, so the file stays readable by builds from before clips.
- `exportEpisodeClip` reads through `VideoPlaybackInfrastructure.bind` without the cache, so it gets the player's
  headers, HLS and AES-128 handling and separate audio. Media3 Transformer clips only with its own source factory, so
  the export wraps the bound one in `ClippingMediaSource`. It takes the lowest rendition at least 540 px on the short
  side and binds only the chosen separate audio track, selecting it over audio inside the stream, scales to 540 px, tone-maps HDR to SDR, and encodes H.264 at
  1.1 Mbit/s with AAC at 128 kbit/s. The result goes through `AnimationExportDestination` into Movies, and Share
  in the download log or the "Clip saved" notification sends it.
- Tracks travel by their position among the stream's sideloaded tracks (`PlaybackTrack.sideloadIndex`), not by
  their on-screen labels, which the player makes unique ("English (2)"). An audio track inside the stream is only
  a preferred label. When the job refreshes links, the chosen tracks are found again in the new stream by label and
  repeat count; a track that is gone, or whose label now repeats a different number of times, is dropped, so the
  clip never silently uses a different one.
- Subtitles: the selected sideloaded track is parsed and drawn per frame by a `SubtitleView` styled
  with the player's own `applyViewerSubtitleStyle`, so the clip matches what was on screen. If the track cannot be
  burned, the clip saves without it and its download item says "Saved without subtitles".
- A `mediaProcessing` foreground service (`EpisodeClipService`) starts when a clip is submitted or retried, shows
  progress, posts "Clip saved" (tap to share) or "Clip failed" for every clip that ends, each in its own
  notification, and stops only when no clip is active and no newer start arrived. Each start names the clips it is
  for by marker, so a clip that fails before the service first looks is still reported, and a retried clip is
  reported once it has run again. The service also stays up until every clip it was started for has reached the
  queue, or that clip's ten-second grace has passed, so a clip still waiting behind another queue command is never
  left unprotected.

## Consequences

Known limits: subtitles carried inside the stream are not burned; an in-stream audio choice is only a preference;
sources below 540 px are upscaled; the strip recentres after a handle is released rather than scrolling while it
is held; and a handle drag shows no frame preview. Encode time on a phone is unmeasured until a phone build.

## Applies to

- `app-logic/src/main/kotlin/com/theoriacodex/app/viewer/EpisodeClipRange.kt`: `EpisodeClipRange`, `initialEpisodeClip`, `episodeClipWindow`
- `media-kit/src/main/kotlin/com/theoriacodex/app/viewer/EpisodeClipExport.kt`: `exportEpisodeClip`, `SubtitleBurnIn`, `ClippingSourceFactory`
- `media-kit/src/main/kotlin/com/theoriacodex/app/media/PostDownloadQueue.kt`: `EpisodeClipRequest`, `PreparedPostDownload.Encode`, `ENCODING`
- `media-kit/src/main/kotlin/com/theoriacodex/app/media/PostDownloadJobStore.kt`: `clipStartMs`, `clipEndMs`
- `media-kit/src/main/kotlin/com/theoriacodex/app/media/PostDownloads.kt`: `submitClip`, `prepareClip`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/state/ViewerStateReducer.kt`: `reduceClipAction`, `reduceViewerState`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerClipStrip.kt`: `ViewerClipStrip`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerVideoClipping.kt`: `ViewerVideoClipping`, `ViewerClipReturn`
- `app/src/main/java/com/theoriacodex/app/media/EpisodeClipService.kt`: `EpisodeClipService`
- `media-kit/src/main/kotlin/com/theoriacodex/app/media/EpisodeClipWatch.kt`: `EpisodeClipWatch`
- `app/src/main/java/com/theoriacodex/app/ui/routes/ViewerRoute.kt`: `clipViewerEpisode`

## Guarded by

- `media-kit/src/androidTest/kotlin/com/theoriacodex/app/viewer/EpisodeClipExportDeviceTest.kt`: `aClipOfAnEncryptedHlsEpisodeKeepsOnlyItsRangeAndBurnsInTheCueShownThen_adr_0223`
- `media-kit/src/test/kotlin/com/theoriacodex/app/media/PostDownloadQueueTest.kt`: `clips encode one at a time with progress and end saved with their note (ADR 0223)`, `cancelling a clip mid-encode ends it cancelled, and an interrupted clip is never retried (ADR 0223)`
- `media-kit/src/test/kotlin/com/theoriacodex/app/viewer/EpisodeClipTracksTest.kt`: `refreshed links keep the chosen tracks by label and repeat, or drop a track that is gone or ambiguous (ADR 0223)`
- `media-kit/src/test/kotlin/com/theoriacodex/app/media/PostDownloadJobStoreGoldenTest.kt`: `a clip keeps only its range across restarts, writes no status older builds lack, and a damaged clip row is dropped alone (ADR 0223)`
- `app-logic/src/test/kotlin/com/theoriacodex/app/viewer/EpisodeClipRangeTest.kt`: `a clip starts at the playhead with the next 30 seconds, sliding back before the episode ends (ADR 0223)`, `handles keep a clip between one second and one minute inside the episode (ADR 0223)`, `the strip shows two minutes centred on the clip, kept inside the episode (ADR 0223)`, `a touch picks the nearer handle within reach and maps back to episode time (ADR 0223)`
- `media-kit/src/test/kotlin/com/theoriacodex/app/media/EpisodeClipWatchTest.kt`: `a clip that fails before it is ever seen running is still reported once (ADR 0223)`, `a retried clip is reported only after it has run again (ADR 0223)`, `the watch stays open for a clip that has not reached the queue until its grace ends (ADR 0223)`
- `feature-viewer/src/test/kotlin/com/theoriacodex/app/viewer/state/ViewerClipStateTest.kt`: `clipping starts only on a watched episode with streams (ADR 0223)`, `saving emits one clip for the episode and leaves clip mode (ADR 0223)`, `moving to another episode drops the selection (ADR 0223)`, `the end of the episode loops the clip instead of moving to the next one (ADR 0223)`
- `feature-viewer/src/test/kotlin/com/theoriacodex/app/viewer/ViewerClipModeTest.kt`: `clip mode swaps the episode chrome for its own bar and saves the selected range (ADR 0223)`
- Left to prose: that the foreground service keeps an encode alive with the app in the background; host tests cannot freeze a process, so this needs a phone check.
