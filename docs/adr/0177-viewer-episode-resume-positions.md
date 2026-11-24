# 0177. Episode positions are kept for the whole session and only reported once a player has reached its start

Status: Accepted

## Context
A Viewer player can be rebuilt (stream switch, fresh links, Retry), short-lived (swiped past), or closing. Each still stands at its unplayed start, and writing that position would overwrite the real one. A finished episode that resumed at its end would end again at once, and in Queue next mode skip straight past it.

## Decision
- `ViewerVideoPositions` keeps positions for the current post and five neighbours either way. A watched session also keeps every episode's position however far the user goes, seeded with `ViewerSeries.Watching.startPositionsMs`, so an episode outside the window resumes where it was left.
- `VIEWER_EPISODE_END_MARGIN_MS` (5 s): in a watched session, a start within it of the end counts as watched to the end and starts over (`startsOver`); other videos resume anywhere. A saved position within the margin of an episode's end likewise makes it start over rather than end at once.
- `ViewerVideoStart` is shared by the players of one showing of a page. Until one has been at its position (`ViewerStartedPlayback.atPosition`), `startsOver` may restart it once the duration is known; after that the video is under way and a replacing player resumes where it stopped, however near the end.
- `ViewerStartedPlayback`: a player is at its position after its start applied (a resume settled once the duration is known and any seek took) and a frame rendered there or playback ran (a stream may carry audio only). After a start-over seek, `shown` is reset so only a frame or playback after that seek counts. Until then nothing is reported: its own start seeks are not the user's.
- Every position report goes through `progress` (seeks via `ViewerSeekReports`, pauses and periodic reports, release via `releaseProgress` and `resumeMs`). `resumeMs` is 0 for an ended video. A drag's instant seeks while scrubbing are one seek, reported at release; `ViewerVideoState.seek` and `release` clear `scrubbing` before seeking so the player's listener counts the seek.
- `ViewerVideoBinding` reads the start after the previous binding's disposal saved its final position, and `latestOnRelease` keeps a stream switch's disposal saving to this page's position; the player pool can hand back the same player after a stream switch, so the lease starts with the right loop mode.
- `ViewerVideoPositionStore` (in the Viewer's ViewModel store) keeps a session's positions while the screen leaves composition on the back stack, so a Viewer level covered by a trail fork resumes its videos. `watching` seeds apply only when the identity starts a new session.
- `recordPlaybackProgress` still writes for a post that is no longer current, because its player's last report arrives as it is swiped away. `clearSession(retainPresentation = true)` keeps series and pages and leaves controls as they were except pausing, because a closing player reads any change as a command (a dropped stream label rebinds it; a reset replay count seeks it to the start); `reportingSession` stays so retained pages still report as players let go.

## Consequences
Resume survives player rebuilds, trail forks and far-away episodes, and no rebuilt player can overwrite a real position with its start. Changing when a player counts as started affects every progress writer.

## Applies to
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerVideoPositions.kt`: `ViewerVideoPositions`, `VIEWER_EPISODE_END_MARGIN_MS`, `ViewerVideoPositionStore`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerVideoPlayer.kt`: `ViewerVideoStart`, `ViewerStartedPlayback`, `ViewerSeekReports`, `ViewerVideoBinding`, `ViewerVideoState.release`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerViewModel.kt`: `recordPlaybackProgress`, `clearSession`, `reportingSession`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerSessionCoordinator.kt`: `ViewerSeries.Watching`
