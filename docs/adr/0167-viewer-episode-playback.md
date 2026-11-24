# 0167. Episode playback keeps the user's track choice across players, reports progress often, and recovers media at most once per ask

Status: Accepted

## Context

Series episodes play as long videos with their own controls in the screen's chrome, not in the player. Media3 never shows an external subtitle by itself, players are rebuilt on stream recovery and episode changes, and a process can be killed mid-episode. Image and episode media can also fail and be asked again from the source.

## Decision

- `ViewerActiveTracks` follows the episode player on screen and publishes its tracks and loading state to the chrome. Every followed player gets the user's `ViewerEpisodeChoice` as its tracks arrive, so subtitles need no re-enabling per stream, recovery or episode. `trackChanges` changes only what a player does not already play: audio the episode does not offer keeps its own; subtitles not offered, or none chosen, show what Media3 picked, else `defaultSubtitle` (the first track whose label names the locale's language, in English, in itself or by its code, as a whole word ignoring case; else the first); only Off turns subtitles off.
- Loading is published separately from track following, so buffering flips never re-apply track choices and loading publishes even while the player is null. `publishLoading` is guarded by an owner token so a stale dispose never clears a newer page's state.
- Only episodes offer tracks: the screen provides `ViewerActiveTracks` while a series is watched, and no other video's player is followed. An episode always plays on to the next, so it offers no playback-mode choice, and an episode never loops.
- Center controls keep touch targets tight so double-tapping around them still zooms. The stream button keeps one accessibility node described `Stream` whose text and state both name its label, matching TalkBack and the live anime viewer tests; its tag and clickable come before `clearAndSetSemantics` so click actions and the test tag remain.
- Progress: `reportPlaybackProgress` reports every `VIEWER_PROGRESS_INTERVAL_MS` while playing and once each time playback stops, so a killed process resumes close to where it was. `episodeProgress` routes it only while a series is watched (other sessions never report), and the series outlives a closing session so the closing player's last report still goes out.
- Media session: external controls belong to the current Viewer, never to concurrently autoplaying feed cards. They play and pause but never set repeat (the repeat command is removed and ignored), because looping is the Viewer's playback mode. Each session gets a unique id because Compose can attach the next page before disposing the previous page's session, and `attachViewerMediaSession` closes the session when the host is below STARTED so a stopped Viewer cannot be commanded into hidden playback.
- Recovery: `recoverMedia` runs within the session's lookup limit (`viewerResolutionTimeoutMs`) and applies a result only if the session and the page's post are unchanged, checked again before the source records it (`stillApplies`), since applying it would replace the newer post with the older one. A recovered post counts only if it brings something the failure lacked: media with streams (an episode) needs different stream links, since the same ones would rebuild the same failing player; other media is taken as new. An episode's lookup runs in the player's coroutine so leaving the page cancels it; an image's ask goes through the route, which looks the post up in its own scope. Image recovery, triggered by an HTTP 404, is asked once per failure while the screen lives (`ViewerImageSession.recoveryRequests`).
- `ViewerVideoRecovery.decide` order: a decoder error retries in place once. Media without streams then fails. An episode stream that rendered a first frame in this load (`hasRenderedFirstFrame`, which a paused player can reach and an audio-only one never does), or whose links were looked up more than `VIEWER_LINKS_OLD_AFTER_MS` (2 min) before the load began, asks for fresh links once on an I/O or parsing error (codes 2000-3999, except `ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED`, since the app refuses plain HTTP whatever the links). Anything else, or fresh links bringing nothing new, falls back to the next stream not yet tried.
- Fallback happens at most `VIEWER_MAX_STREAM_FALLBACKS` (3) times per load and never when the playing stream is the one the user picked by hand (`ViewerEpisodeStreams.pickedLabel`); then the failure shows with the stream picker. `ViewerLoadAttempts.fallbacks` is counted separately from `failedStreams`, because fresh links can drop the stream fallen back to and play one that failed before.
- The ask for fresh links is spent at the failure position (`askSpentAtMs`) and comes back only once the player's observed position reaches `VIEWER_RECOVERED_PROGRESS_MS` (30 s) past it (a seek or scrub can advance it), or when its lookup is cancelled (`onAskCancelled`). A rendered frame is not enough: every lookup returns newly signed links, so a stream dying at a fixed point (a missing segment, a host cutting the file at an offset) would resume just before it, render, fail and ask again forever.
- A manual Retry (`loadGeneration` above 0) of an episode that has not played opens with the ask (`opening`) instead of replaying the dead address; if nothing new comes, the address replays in place once. `retryMedia` in the reducer therefore does not also request a route recovery for media with streams (it would look the episode up twice), and a failed, recoverable page lookup is re-run alone.
- `ViewerLoadAttempts` outlive the players of a load and are held per session (`ViewerStreamAttempts.forSession` clears them for another session), so a recovered stream that fails too moves on instead of asking in a loop.
- `ViewerVideoKey` includes the playing stream's label, because two streams of an episode can share one request; moving between them must still replace the failed player. A new key (fresh links, another stream, Retry) is a new player and a fresh `ViewerVideoRecovery`.
- In `ViewerVideoStreamRecovery`/the player listener, a terminal or recovery-in-progress state (`state.pending`) ignores later errors. A Retry's opening ask holds the player back so the address that failed is not replayed before fresh links come.
- A hand pick (`ViewerEpisodeStream.pickedByHand`) gets no automatic fallback; a fallback applies to that episode only and leaves the session's choice for later episodes alone. `chooseStream` replaces every episode's own stream with the new session choice, keeps the episode playing as picked by hand, and clears a failure shown on that media so the new stream starts at once. Choices are kept by label because links and track ids change between episodes and players.
- `ViewerRecoveryDecision.RetryInPlace` binds a new player for the same request, since a failed decoder may work a second time. A video ending in `ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED` shows the app's own reason (`viewerVideoFailureMessage`).
- `resolvedAtMs` (when the post's media was last looked up) is kept on the page because an episode's links expire with age.

## Consequences

New players for episodes must be followed through `FollowViewerActiveTracks`. Changing the progress interval changes how far a killed process can lose.

## Applies to

- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerEpisodeControls.kt`: `ViewerActiveTracks`, `trackChanges`, `defaultSubtitle`, `FollowViewerActiveTracks`, `ViewerEpisodeCenterControls`, `ViewerStreamButton`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerPlaybackProgress.kt`: `reportPlaybackProgress`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerPostPager.kt`: `episodeProgress`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerScreenBindings.kt`: `ViewerScreenHost.onPlaybackProgress`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerMediaSession.kt`: `createViewerMediaSession`, `attachViewerMediaSession`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerMediaRecovery.kt`: `recoverMedia`, `ViewerMediaRecoveryRequest`, `refreshes`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerImageRenderer.kt`: `ViewerImageSession`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerDockBinding.kt`: `viewerDockActions`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerVideoRecovery.kt`: `ViewerVideoRecovery`, `ViewerLoadAttempts`, `ViewerStreamAttempts`, `ViewerEpisodeStreams`, `ViewerVideoKey`, `VIEWER_LINKS_OLD_AFTER_MS`, `VIEWER_RECOVERED_PROGRESS_MS`, `VIEWER_MAX_STREAM_FALLBACKS`, `viewerVideoFailureMessage`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerVideoPlayer.kt`: `ViewerVideoStreamRecovery`, the `ViewerVideoKey` handling in `ViewerVideoPlayer`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/state/ViewerEpisodeChoice.kt`: `ViewerEpisodeChoice`, `ViewerEpisodeStream`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/state/ViewerStateReducer.kt`: `chooseStream`, `retryMedia`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/state/ViewerUiState.kt`: `ViewerResolutionState.resolvedAtMs`
