# 0224. Subtitle size is a saved setting; subtitle delay shifts the text renderer's clock for the session

Status: Accepted

## Context

Episode subtitles are sometimes out of sync with the video, and their size suits some screens and sources but not
others. The owner wants to fix both while an episode plays and watch the subtitles change. Media3's `TextRenderer`
has no delay setting and is final. Sideloaded subtitle files enter the player as one sample at time zero
(`SingleSampleMediaSource`) and are decoded whole; subtitles inside the stream are parsed as the media is read, and
the text renderer reads at most one second ahead of the position it is given.

## Decision

- Controls: the Subtitles sheet shows a "Size and delay" row while a track is on. It closes the sheet and opens a
  floating panel at the top (`ViewerPanel.SUBTITLE_TUNING`), and the Viewer hides its chrome while the panel is open,
  even under TalkBack, so the subtitles stay visible while the episode plays. It is a chrome panel, so starting a
  clip, locking or toggling the chrome closes it, and the chrome comes back as it was. Each value has − and +
  buttons that repeat while held and turn off at their limit. A reset button shows only when a value is not the
  default. A tap outside the panel or Back closes it. Changes are sent as relative steps
  (`SubtitleAdjustment`), so fast taps never apply a stale value.
- Size: 50–200 % in 10 % steps (`SubtitleTuning`). It multiplies the system caption scale when the user has
  captions turned on in Android, so it never overrides an accessibility choice. It is global and saved in
  `ViewerSettings.subtitleSizePercent`, written as `viewerSubtitleSizePercent` only when it is not 100, so default
  settings and backups keep their bytes. A stored value is snapped to a step on read; a backup with a value outside
  50–200 is rejected.
- Delay: 0.1 s steps up to 30 s either way. A positive delay shows subtitles later. It belongs to the Viewer
  session (`ViewerEpisodeChoice.subtitleDelayMs`): it carries across episodes and ends at zero when the Viewer
  closes, since an offset fits one release of one series, not every video.
- Mechanism: the text renderer is wrapped in `DelayedTextRenderer`, a `ForwardingRenderer` that hands the renderer
  `playback position − delay` wherever Media3 passes a position (enable, reset, render, progress). A new delay is
  sent as a player message on the playback thread, then the player reselects only the subtitle track: the Viewer's
  track selector (`SubtitleReloadingTrackSelector`) tags each text selection with a reload count, so a bumped count
  makes the text selection differ while video and audio selections stay equal. Media3 then swaps in a fresh text
  stream and re-enables the text renderer at the shifted time, and leaves the video and audio renderers and their
  buffers alone, so playback never rebuffers. A sideloaded file is delivered again from its single sample; a
  subtitle rendition in its own HLS playlist re-reads its buffered samples, or reloads only that playlist. This
  replaced a 1 ms seek, which flushed every renderer and rebuffered the video on each change. The reload is skipped
  when the player is idle or ended; there the new delay applies from the next render. The Viewer sends a
  change after it has settled for 250 ms, so a held button causes one reload, not twenty, and only while the episode
  plays: with the legacy subtitle decoding the app uses, Media3 publishes reloaded cues only from a started renderer, so a reset while paused would blank the
  subtitle until playback resumed. A change made while paused applies on resume.
- Ending: Media3 ends a text renderer at the period end only through an `instanceof TextRenderer` call in
  `RendererHolder` (`setFinalStreamEndPositionUs`), which the wrapper does not receive. Otherwise a text renderer
  ends only once its cues run out, and it reads its end of stream only when its own clock is within a second of the
  end, so a cue past the video's end, or any positive delay, would hold the player on the last frame.
  `DelayedTextRenderer.isEnded` therefore also reports ended once playback passes the period's duration, at every
  delay. Media3 enters ENDED only on a final period, so episodes end and autoplay on time. The other lost case,
  server-side ad insertion transitions, does not apply, since no source uses it.
- Clips: a clip burns subtitles at the Viewer's size and delay when Save was pressed (`EpisodeClipTracks.subtitleTuning`).
  The burn-in reads each frame's cues at `clip start + frame time − delay`.

## Consequences

Subtitles inside the stream have a gap under a positive delay: right after a seek or a delay change, the sample
queue starts from the keyframe at or before the playback position, so cues that began within the delay before it
are skipped until the next cue. Under a negative delay the renderer needs cues the delay ahead of playback, so
after a seek, at the start of playback, or after a delay change, cues inside that window can load after their
shifted time and are skipped or shown late until the buffer passes the delay. Sideloaded files, the common case, have
no gap because the whole file is one sample. A looping video with a text track and a positive delay over a second
pauses on its last frame for about the delay minus a second each loop, because Media3 loops only after every
renderer has read to the end; episodes do not loop, and clip mode seeks back before the end. Captions carried inside an HLS video playlist (CEA-608/708) share that playlist's loader, so when their buffered samples no longer reach back to the playback position, the reload restarts that loader and the video rebuffers once, as the old seek did; no fixture carries such captions. Size scales text subtitles from every source; image-based subtitles (PGS, VobSub) keep their size; delay is not
remembered per series.

## Applies to

- `core-domain/src/main/kotlin/com/theoriacodex/domain/model/SubtitleTuning.kt`: `SubtitleTuning`, `SubtitleAdjustment`, `subtitleSizePercent`, `subtitleDelayMs`
- `media-kit/src/main/kotlin/com/theoriacodex/app/viewer/SubtitleDelay.kt`: `DelayedTextRenderer`, `setSubtitleDelay`, `subtitleClockUs`, `SubtitleReloadingTrackSelector`
- `media-kit/src/main/kotlin/com/theoriacodex/app/viewer/ViewerSubtitleStyle.kt`: `applyViewerSubtitleStyle`, `viewerSubtitleTextFraction`
- `media-kit/src/main/kotlin/com/theoriacodex/app/viewer/EpisodeClipExport.kt`: `clipSubtitleSourceOffsetUs`, `activeAt`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/RepositoryStoreRecords.kt`: `viewerSubtitleSizePercent`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/ProfileBackupSettingsValidation.kt`: `viewerSubtitleSizePercent`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerSubtitleTuning.kt`: `ViewerSubtitleTuningPanel`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/state/ViewerStateReducer.kt`: `AdjustSubtitleDelay`

## Guarded by

- `core-domain/src/test/kotlin/com/theoriacodex/domain/model/SubtitleTuningTest.kt`: `size steps by ten percent within 50 to 200 and reset returns 100 (ADR 0224)`, `delay steps by a tenth of a second in both directions up to thirty seconds and reset returns zero (ADR 0224)`
- `media-kit/src/test/kotlin/com/theoriacodex/app/viewer/SubtitleDelayTest.kt`: `the subtitle renderer runs behind playback by a positive delay and ahead of it by a negative one (ADR 0224)`, `subtitles end with the video at any delay, never holding playback past its end (ADR 0224)`, `a clip burns each cue in the delay later, measured from the clip's own start (ADR 0224)`, `a delay change reselects only the subtitle track, so video and audio keep their streams (ADR 0224)`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/DataStoreRepositoriesTest.kt`: `a stored subtitle size snaps to a supported step and missing means the default (ADR 0224)`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/BackupGoldenTest.kt`: `a backup's subtitle size must be within 50 to 200 percent (ADR 0224)`
- `feature-viewer/src/test/kotlin/com/theoriacodex/app/viewer/ViewerEpisodeControlsTest.kt`: `the subtitle sheet offers size and delay only while a track is on, and opens their panel (ADR 0224)`, `the size and delay panel steps each value, resets both from one button, and closes on a tap outside it but not on it (ADR 0224)`, `holding a step button repeats until its limit, and letting go adds no extra step (ADR 0224)`
- `feature-viewer/src/test/kotlin/com/theoriacodex/app/viewer/ViewerWatchingTest.kt`: `the size and delay panel closes with the chrome, and the delay carries across episodes and sessions it replaces but starts at zero in a new one (ADR 0224)`
- `media-kit/src/androidTest/kotlin/com/theoriacodex/app/viewer/StreamPlaybackDeviceTest.kt`: `aPositiveSubtitleDelayShowsCuesLaterAndPlaybackStillEndsWithTheVideo_adr_0224`, `aNegativeSubtitleDelayChosenDuringPlaybackShowsCuesEarlier_adr_0224`
- `media-kit/src/androidTest/kotlin/com/theoriacodex/app/viewer/EpisodeClipExportDeviceTest.kt`: `aClipBurnsSubtitlesAtTheChosenSizeAndDelay_adr_0224`
- Left to prose: the embedded-track gaps under positive and negative delays; no fixture carries subtitles inside the stream.
