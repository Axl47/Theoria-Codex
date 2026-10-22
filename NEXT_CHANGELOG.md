# Changes since v0.11.0

## New

- Swipe up or down on the left or right of fullscreen videos to adjust brightness or media volume, with a centered level indicator.

- Browse Likes, Followed, and Saved Searches together from the new Saved overview.
- Combine selected Saved Searches into a live feed, with independent loading and Retry for each query.
- Use Search Undo to revisit previous queries and source selections in the current session.
- Clean a Codex by removing posts already present in selected collections, with Retry and Undo.
- View browsing time, watching time, post opens, and top posts for individual Codices in Settings.
- Browse downloads in a thumbnail grid.
- Open saved files from download history.
- Remove entries from download history.
- Delete downloaded files individually or in bulk.
- Filter Recents by source.
- Preview timeline thumbnails while scrubbing videos and animations, then seek when you release.
- Automatically enter picture-in-picture when leaving Viewer during video playback.

## Improvements

- Use a monochrome launcher icon for non-production builds so they are easier to distinguish from the production app.
- Animate swipes between fullscreen posts and gallery media.
- Expand tapped post media from its feed card into fullscreen.
- Shrink fullscreen media back into its feed card on close, bringing the current post near the center first.

- Simplify Search source selection with centered icons in separate rounded chips, a compact R34 submenu, and highlights for sources included in Unified.

- Show the destination Codex’s name in post save confirmations.

- Keep Saved sub-Codex feeds loaded for the session when reopening them.
- Shorten the Saved Searches title to “Searches”.
- Show a right arrow beside each Saved sub-Codex title to make opening it clearer.

- Resume videos where you left them when revisiting up to five nearby Viewer posts in either direction, until you exit Viewer.
- Keep the last seek thumbnail visible beneath the spinner while the next frame loads.
- Label the Codex tab header “Codex Library”.
- Hold a Codex title or Saved section name to open its actions or management sheet.
- Reduce vertical spacing around the For You source dropdown.
- Reduce vertical spacing around the Search toolbar.
- Search collections in Save to Codex.
- Show a green highlight and checkmark for Codices that already contain the post in Save to Codex.
- Preload Save to Codex tag matching when opening a post’s hold menu to reduce waiting in the save picker.
- Prioritize Save to Codex destinations relevant to the applied search and incoming post tags, with an option to return to library order.
- Activate Unified Search by holding sources, with the current single source hidden from the picker.
- Adjust only the selected Unified sources’ weights in Settings using stepped sliders.
- Automatically reapply Search when returning after changing its Unified weights.
- Remember Viewer play mode.
- Adjust playback speed with a slider.
- Show a thin playback progress bar while fullscreen controls are hidden.
- Use black media backgrounds.
- Hide the gallery counter for single-media posts.
- Show cached Viewer previews sooner.
- Show the first Pixiv Ugoira frame sooner.
- Reduce lag when opening or switching animated posts.
- See storage usage in a chart with a category breakdown.
- Use more compact collection actions, with matching toolbar icons for Clean Codex and Make available offline.
- Use more compact filter controls.
- Use more compact applied-search summaries.
- Improve accessibility labels for tag actions.
- Improve touch targets for tag actions.
- Make the post hold menu more compact with half-height include/exclude buttons.
- Remove repeated type labels from tag names in the post hold menu.

## Fixes
- Resume autoplaying feed videos at the position where you left fullscreen playback.
- Open nhentai page links at the linked gallery page, including when pages load after the cover.
- Close Codex when using Go to Search, including from its Viewer.
- Make Viewer error Retry buttons respond to taps.
- Clear stale Viewer errors when video playback succeeds, and keep post-resolution timeouts from interrupting loaded media.
- Extend the landscape navigation sidebar background to the bottom of the screen.
- Automatically retry a video once after a decoder or rendering failure, keeping your playback position.
- Restore the previous search’s scroll position when using Search Undo.
- Keep the background around the notification bar black in landscape.
- Dismiss the keyboard when swiping between tabs or leaving Search input.

- Collapse expanded Search controls when scrolling results, while preserving pending edits.
- Restore saved Search positions through loading and pagination.
- Reset Search to the top after Apply.
- Preserve Library scroll when returning from a collection.
- Keep videos, GIFs, and Pixiv animations playing while dragging the seek bar without resuming paused playback.
- Prevent forward swipes from skipping unresolved galleries.
- Show restoration progress only during actual session recovery.
- Preserve related-post shelf navigation after liking from fullscreen.
- Keep previous pages reachable after empty results.
- Show related posts after liking creator uploads.
- Show nhentai’s “More like this” recommendations after liking a post.
- Refresh Followed covers from the first available post in the selected feed order.
- Preserve provider tag counts.
- Show counts for typed terms in the tag hold menu.
- Rank suggestions using available counts.
- Keep tag-sheet selections consistent with the Search draft when switching between included and excluded terms.
- Remember Unified source weights for each enabled-source combination.
- Use the artist as the nhentai author.
- Remove bracketed annotations from nhentai titles.
- Preserve For You history when clearing ordinary searches.
- Count only successful searches in lifetime statistics.
- Keep successful provider results visible when another source fails during pagination.
- Improve download recovery after app restarts, including interrupted animation exports, without duplicating uncertain transfers.
- Improve offline-save recovery after app restarts, including newly queued offline posts.
- Make profile deletion recoverable after interruption.
- Retain Like changes across navigation with Retry feedback when needed.
- Retain preference changes across navigation with Retry feedback when needed.
- Keep Automatic Codex tags in a compact scrollable list.
