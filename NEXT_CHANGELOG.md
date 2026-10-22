# Changes since v0.11.0

## New

- Browse Likes, Followed, and Saved Searches together from the new Saved overview.
- Combine selected Saved Searches into a live feed, with independent loading and Retry for each query.
- Use Search Undo to revisit previous queries and source selections in the current session.
- Clean a Codex by removing posts already present in selected collections, with Retry and Undo.
- View browsing time, watching time, post opens, and top posts for individual Codices in Settings.
- Manage downloads in a thumbnail grid: open saved files, remove history, or delete individual and multiple files.
- Filter Recents by source.
- Preview timeline thumbnails while scrubbing videos and animations, then seek when you release.
- Automatically enter picture-in-picture when leaving Viewer during video playback.

## Improvements

- Search collections in Save to Codex and prioritize destinations relevant to the applied search and incoming post tags, with an option to return to library order.
- Choose Search sources from a compact dropdown, including long-press selection for Multi-Search.
- Remember Viewer play mode and adjust playback speed with a slider.
- Show a thin playback progress bar while fullscreen controls are hidden, use black media backgrounds, and hide the gallery counter for single-media posts.
- Show cached Viewer previews and the first Pixiv Ugoira frame sooner, and reduce lag when opening or switching animated posts.
- See storage usage in a chart with a category breakdown.
- Use more compact collection actions, filter controls, and applied-search summaries.
- Improve accessibility labels and touch targets for tag actions.
- Make the post hold menu more compact with half-height include/exclude buttons and tag names without repeated type labels.

## Fixes

- Restore saved Search positions through loading and pagination, reset to the top after Apply, and preserve Library scroll when returning from a collection.
- Keep videos playing while dragging the seek bar without resuming paused playback.
- Prevent forward swipes from skipping unresolved galleries and show restoration progress only during actual session recovery.
- Preserve related-post shelf navigation after liking from fullscreen, keep previous pages reachable after empty results, and show related posts after liking creator uploads.
- Refresh Followed covers from the first available post in the selected feed order.
- Preserve provider tag counts, show counts for typed terms in the tag hold menu, and rank suggestions using available counts.
- Keep tag-sheet selections consistent with the Search draft when switching between included and excluded terms.
- Remember Unified source weights for each enabled-source combination.
- Use the artist as the nhentai author and remove bracketed annotations from titles.
- Preserve For You history when clearing ordinary searches and count only successful searches in lifetime statistics.
- Keep successful provider results visible when another source fails during pagination.
- Improve download and offline-save recovery after app restarts, including interrupted animation exports and newly queued offline posts, without duplicating uncertain transfers.
- Make profile deletion recoverable after interruption and retain Like and preference changes across navigation with Retry feedback when needed.
- Keep Automatic Codex tags in a compact scrollable list.
