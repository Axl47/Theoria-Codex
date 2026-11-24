# 0178. Viewer session state survives live updates of the same session, and series entries follow their own rules

Status: Accepted

## Context
The Viewer's pages grow while open (feed pagination, related shelf), and the owner outlives Activity and renderer attachment. Series sessions (chapters and episodes) differ from ordinary posts. Replacing state naively would reopen hidden chrome, end a slideshow, or reset a user's episode choices.

## Decision
- `replaceSession` in the reducer: a replacement for the same session keeps chrome visibility, a running slideshow, lock and fill-screen (only for a watched series), episode choices and the related branch. A different session starts from defaults.
- `ViewerSession.posts` are the launch payload and never change; the live list is `ViewerUiState.pages`. `onToggleLike` is a transient launch-owner action (cold restoration never invents a related-shelf owner); `saveQuery` is the applied query captured for contextual save ranking and is never inferred from another live route.
- `ViewerSeries`: every rule for episodes hangs off `Watching` (or off media having streams where the rule is about media, as for prefetch). A series' entries are never collected: the top bar names the entry in place of the source, Like and Save are hidden, and the info sheet holds only Start over. `ViewerUiState.series` is kept by the presentation a closing Viewer retains so every rule reads one source after the session ended.
- `startingPlaybackMode`: a watched series always starts in `NEXT` mode (an episode goes on to the next one, never loops); otherwise the app's setting applies.
- A launch media index past the listing's media (for example a gallery link opened from its cover) waits as pending for the resolution (`toNewViewerPageState`).
- The Viewer's saved state holds only the compact identity and current selection; posts are process-local and ExoPlayer, decoders and launchers stay in renderers. `ViewerControlsState.slideshow` is session-only and never saved.
- `ViewerControlsState.locked` blocks media gestures and chrome while watching episodes and survives episode advance; `fillScreen` is kept separate from track choices so toggling never re-applies tracks.
- `ViewerRelatedBranch`/`includeRelatedPosts`: adding branch posts never changes which pages belong to the original feed, which the bottom strip shows. `appendPosts` builds pages only for the appended posts, so a feed page costs what it adds.
- `recordVisiblePost` retains one-shot visibility admission and its write across renderer/Activity reattachment; a partial best-effort write must not become a duplicate lifetime event on reattachment, so `runCatchingPreservingCancellation` is used.
- The bottom strip asks the source for more posts (`ViewerAction.LoadMore`) at most once per original-feed size, and only while the source has more and no load is running (`rememberViewerLoadMore`).
- Slideshow: the hold is timed by `delay` and only drawn by an animation, so disabled system animations cannot shorten it; time spent in the background or with the screen off does not count, and the hold restarts on return (`ViewerSlideshowControl`).
- Post enrichment can fail independently of a working media URL, so on the current page a recoverable resolution failure whose selected media is already ready is recorded on the page but raises no media error (`resolutionFailed`).
- `ViewerAction.ResolutionCompleted.recovered`: the lookup asked again for media that failed while its page showed it; the load continues on the new media, so each media keeps its load generation (what a Retry started and what the load has tried) and a current page keeps its playback controls (paused stays paused).

## Consequences
Pagination and related-shelf loads never disturb what the user sees. Series behaviour is concentrated in `ViewerSeries`. Adding session-scoped controls means deciding whether they survive a live update in `replaceSession`.

## Applies to
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/state/ViewerStateReducer.kt`: `replaceSession`, `appendPosts`, `includeRelatedPosts`, `toNewViewerPageState`, `resolutionFailed`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/state/ViewerUiState.kt`: `ViewerControlsState`, `ViewerUiState.series`, `ViewerRelatedBranch`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/state/ViewerAction.kt`: `ResolutionCompleted`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerSessionCoordinator.kt`: `ViewerSession`, `ViewerSeries`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerViewModel.kt`: `startingPlaybackMode`, `recordVisiblePost`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerSlideshowControl.kt`: `ViewerSlideshowControl`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerScreen.kt`: `rememberViewerLoadMore`
