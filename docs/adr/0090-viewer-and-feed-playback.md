# 0090. Feed players start after a short delay and Viewer rotation is emphasised only where it gains space

Status: Accepted

## Context
Scrolling feeds should not bind players or load animations for cards that pass quickly, and a paused card keeps its last frame until the Viewer opening covers it. Rotation is only useful for media that is clearly wide or tall.

## Decision
- A card must stay visible `FEED_PLAYER_ACTIVATION_DELAY_MS` (180 ms) before costly work starts, and a player is released only after `FEED_PLAYER_RELEASE_GRACE_MS` (1 s), long enough for an opening Viewer to cover the paused card.
- Rotation gains real space only when media is at least `VIEWER_ROTATION_MIN_ASPECT` wider than tall or taller than wide (`viewerFillOrientation`; near-square or unknown gives null).
- `viewerFitsOtherWay`: media clearly shaped for the orientation the screen lacks gets an emphasised rotate button; rotate itself is always available while unlocked (ADR 0174).
- Image preloads cover only cards just past the visible end, only on unmetered networks (checked per batch), and are keyed out of the memory cache (`memoryCacheKey = null`) so the card's own request still matches. The metered check is a system call, so a card runs it when picking its stream, not on every recomposition.
- A card releases its player after `FEED_PLAYER_RELEASE_GRACE_MS` so a Viewer opening does not blank the card behind it; a Viewer that opened and closed within it stopped the leased player, so the card resumes it. Players activate after `FEED_PLAYER_ACTIVATION_DELAY_MS` and not while the Viewer is closing, since attaching blocks the main thread; cards show posters then start together.
- Cards only draw their bitmap, so decoding allows hardware bitmaps. `showPlayerIn` skips same-player assignments so only real attaches are traced. Animated drawables skip the memory cache, so the poster stays up while they decode; whole-file animation upgrades and provider detail requests happen only for cards whose playback is active, so scrolling past cards spends no rate limit and a caption alone retains no autoplay demand. The detail request effect keys on whether a resolver exists, not on its identity, and calls the latest resolver, so a new callback instance does not restart the request.
- `videoUrlComesFromPostDetails` is the one place naming sources whose list results carry only a poster.
- A preview selects only its own page's locations; a fallback never shows a different page.
- A watched post gets its own mark because a bare "2h ago" over a result reads as its upload date.
- Presentation trace slices differ from the first-frame section the benchmarks count.

## Consequences
Autoplay of visible cards is preserved; only start and release timing is tuned.

## Applies to
- `app-logic/src/main/kotlin/com/theoriacodex/app/search/FeedPlaybackPolicy.kt`: `FEED_PLAYER_ACTIVATION_DELAY_MS`, `FEED_PLAYER_RELEASE_GRACE_MS`
- `app-logic/src/main/kotlin/com/theoriacodex/app/viewer/ViewerOrientationPolicy.kt`: `VIEWER_ROTATION_MIN_ASPECT`, `viewerFillOrientation`, `viewerFitsOtherWay`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/search/FeedImagePreloader.kt`: `FeedImagePreloadEffect`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/search/FeedMediaComponents.kt`: `showPlayerIn`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/search/SearchResultCard.kt`: `videoUrlComesFromPostDetails`, `SearchCardImageUpgradeEffect`, `SearchResultCard`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/search/PostWatchPresentation.kt`: `postForCardPreview`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/viewer/PreviewPresentationTrace.kt`: `PreviewPresentationTrace`
