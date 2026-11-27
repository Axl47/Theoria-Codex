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
  floating panel at the top (`ViewerPanel.SUBTITLE_TUNING`) with chrome hidden, so the subtitles stay visible while
  the episode plays. Each value has − and + buttons that repeat while held. A reset button shows only when a value
  is not the default. A tap outside the panel or Back closes it. Changes are sent as relative steps
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
  sent as a player message on the playback thread, then the player seeks 1 ms back, because a seek to the current
  position does nothing, and the reset makes the renderer reload cues for the shifted time. The Viewer sends a
  change after it has settled for 250 ms, so a held button causes one seek, not twenty.
- Wrapping loses two `instanceof TextRenderer` cases in Media3's `RendererHolder`. The final-stream end position is
  only an early end signal: the wrapped renderer still ends from the period duration Media3 passes as the stream
  end. Server-side ad insertion transitions do not apply, since no source uses it.
- With a positive delay, the subtitle clock reaches the end of the stream after the video does, so playback would
  wait in BUFFERING for the delay. `DelayedTextRenderer.isEnded` also reports ended once playback passes the final
  period's duration, so episodes still end and autoplay on time.
- Clips: a clip burns subtitles at the Viewer's size and delay when Save was pressed (`EpisodeClipTracks.subtitleTuning`).
  The burn-in reads each frame's cues at `clip start + frame time − delay`.

## Consequences

Subtitles inside the stream have a gap under a positive delay: right after a seek or a delay change, the sample
queue starts from the keyframe at or before the playback position, so cues that began within the delay before it
are skipped until the next cue. Sideloaded files, the common case, have no gap because the whole file is one
sample. A seek takes one rebuffer each time the delay settles. Size applies to every source; delay is not
remembered per series.

## Applies to

- `core-domain/src/main/kotlin/com/theoriacodex/domain/model/SubtitleTuning.kt`: `SubtitleTuning`, `SubtitleAdjustment`, `subtitleSizePercent`, `subtitleDelayMs`
- `media-kit/src/main/kotlin/com/theoriacodex/app/viewer/SubtitleDelay.kt`: `DelayedTextRenderer`, `setSubtitleDelay`, `subtitleClockUs`
- `media-kit/src/main/kotlin/com/theoriacodex/app/viewer/ViewerSubtitleStyle.kt`: `applyViewerSubtitleStyle`, `viewerSubtitleTextFraction`
- `media-kit/src/main/kotlin/com/theoriacodex/app/viewer/EpisodeClipExport.kt`: `clipSubtitleSourceOffsetUs`, `activeAt`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/RepositoryStoreRecords.kt`: `viewerSubtitleSizePercent`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/ProfileBackupSettingsValidation.kt`: `viewerSubtitleSizePercent`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerSubtitleTuning.kt`: `ViewerSubtitleTuningPanel`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/state/ViewerStateReducer.kt`: `AdjustSubtitleDelay`

## Guarded by

- `core-domain/src/test/kotlin/com/theoriacodex/domain/model/SubtitleTuningTest.kt`: `size steps by ten percent within 50 to 200 and reset returns 100 (ADR 0224)`, `delay steps by a tenth of a second in both directions up to thirty seconds and reset returns zero (ADR 0224)`
- `media-kit/src/test/kotlin/com/theoriacodex/app/viewer/SubtitleDelayTest.kt`: `the subtitle renderer runs behind playback by a positive delay and ahead of it by a negative one (ADR 0224)`, `a positive delay lets playback end when the final stream reaches its end, not the delay later (ADR 0224)`, `a clip burns each cue in the delay later, measured from the clip's own start (ADR 0224)`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/DataStoreRepositoriesTest.kt`: `a stored subtitle size snaps to a supported step and missing means the default (ADR 0224)`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/BackupGoldenTest.kt`: `a backup's subtitle size must be one the Viewer offers (ADR 0224)`
- `feature-viewer/src/test/kotlin/com/theoriacodex/app/viewer/ViewerEpisodeControlsTest.kt`: `the subtitle sheet offers size and delay only while a track is on, and opens their panel (ADR 0224)`, `the size and delay panel steps each value, resets both from one button, and closes on a tap outside (ADR 0224)`
- `feature-viewer/src/test/kotlin/com/theoriacodex/app/viewer/ViewerWatchingTest.kt`: `subtitle delay opens over hidden chrome, carries across episodes and sessions it replaces, and starts at zero in a new one (ADR 0224)`
- `media-kit/src/androidTest/kotlin/com/theoriacodex/app/viewer/StreamPlaybackDeviceTest.kt`: `aPositiveSubtitleDelayShowsCuesLaterAndPlaybackStillEndsWithTheVideo_adr_0224`, `aNegativeSubtitleDelayChosenDuringPlaybackShowsCuesEarlier_adr_0224`
- `media-kit/src/androidTest/kotlin/com/theoriacodex/app/viewer/EpisodeClipExportDeviceTest.kt`: `aClipBurnsSubtitlesAtTheChosenSizeAndDelay_adr_0224`
- Left to prose: the embedded-track gap under a positive delay; no fixture carries subtitles inside the stream.
