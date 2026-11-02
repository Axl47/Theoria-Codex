# Changelog

## [Unreleased]

### Changed

- Read a prominent Highlights summary first in each release, with detailed changes available under “Full details” and larger, bold section headings with separators.

- Keep For You row actions collapsed behind a chevron until opened.

- Show person-plus and person-check icons for artist follow actions.

- Dismiss For You rows and undo dismissal without refreshing the other searches or their posts.
- Center the For You source selector in the overview header and remove redundant source labels above search rows.
- Re-seed the first For You feed when refreshing the overview.

- Skim the in-app changelog with collapsible, highlight-colored version titles; older versions start collapsed.

- Gelbooru’s Animated filter now matches any of animated, animation, video, gif, or animated_gif.

### Added

- Reorder For You searches with the same drag handles and accessible move controls as Codex.
- Create an additional personalized search at the bottom of For You with New search.
- Move a For You search into Saved Searches with Save to Codex, removing its row only after saving succeeds.

- Browse personalized interest sections and an exploration section in For You, with previews that open the full feed or Viewer.
- Pin For You sections in Saved Searches, dismiss unwanted interests, and shuffle suggestions while keeping pins.
- Find more focused For You sections through co-occurring liked tags, provider tag counts, and the smart Codex tag-matching lookup.

- Open a collapsible “More like this” thumbnail stripe after liking in fullscreen, and jump to related posts without leaving the viewer.
- Like posts directly from Searches and Followed feeds, including their previews in Saved.
- Collapse Search into a compact strip attached to the top-right corner, with a rounded left edge and closer search and source buttons; restore the previous layout with Legacy “Old Search Bar”.
- Use Legacy Instant Seek to seek continuously while dragging instead of previewing timeline thumbnails.
- Jump between fullscreen posts with a centered seven-thumbnail strip below the seek bar, with evenly spaced thumbnails and matching screen-edge gaps.
- Swipe up or down on the left or right of fullscreen videos to adjust brightness or media volume, with a compact level indicator at the top of the video.
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

### Changed

- Show Search’s “Continue loading” control at the bottom of the results grid.

- Move the Recents source dropdown into the header, replacing the Saved searches shortcut now available in its own subcodex.
- Use the same trash icon as For You for clearing Recents.

- Show brightness and volume indicators at half their previous size, just inside the top of the video.
- Find fullscreen downloads in the post’s long-press menu.
- Scroll the fullscreen thumbnail strip through the full post list, loading more posts as you reach the end.
- Find Invert scroll direction in the new Preferences category.
- Preserve the feed card’s rounded corners and crop while expanding media into fullscreen and returning it to the grid.
- Show Codex post counts in cover badges, with compact titles and aligned overflow menus.
- Give Saved a full-width library card with a larger title and separate, tappable Likes, Followed, and Searches counts.
- Expand Search controls when changing sources.
- Dismiss fullscreen media with a quick downward slide when its feed card is unavailable.
- Keep playback settings open while adjusting options; close them on an outside tap or after five seconds without interaction.
- Make fullscreen posts and gallery media follow your finger while swiping, including when cancelling a swipe.
- Keep the app responsive while preparing large Codices for fullscreen viewing.
- Focus support on Android 16 and Android 17; new builds require Android 16 or newer.
- Use Android’s configured interaction sounds for Compose controls.
- Hide post titles in Saved sub-Codex rows for compact, evenly sized previews.
- Open artist profiles from nhentai posts to browse their galleries.
- Match post source badges to the height of the last watched timestamp.
- Keep “More like this” previews consistently sized so tall posts do not dominate the row.
- Use consistently sized previews in Saved sub-Codex rows.
- Show watched progress and the last watched time on Search and For You results, matching Recents.
- Keep post previews on the last media viewed when returning from fullscreen, including in Recents.
- Resume multi-media posts at the last viewed item when opening them from Search, For You, or Recents, including when swiping between fullscreen posts.
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
- Adjust selected Unified sources’ weights in 5% steps, with other weights updating immediately while the chosen weight stays fixed.
- Collapse Unified Mode settings when browsing a single source.
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

### Fixed

- Prevent shuffled For You interests and Explore from failing with a comparison-contract error.

- Restore thumbnails for downloaded videos and exported animations after reopening the app, using the saved files.

- Smooth the grid’s movement when collapsing the search bar by animating the remaining toolbar gap.
- Wait until fullscreen finishes closing before Hide liked, Hide saved, and Hide watched remove its posts from the feed.
- Keep fullscreen recommendations out of the bottom strip and preserve the original feed order, returning to its next post after the last recommendation.
- Apply the app’s dark sheet theme to Followed Authors, matching Saved Searches.
- Look beyond already-visible Pixiv recommendations before reporting “No new results”.
- Reduce timing-related video playback failures.
- Include navigation fixes for predictive-back races and transition clipping.
- Return fullscreen Saved previews to their sub-Codex cards, scrolling the carousel to the exited post.
- Show tag post counts supplied by nhentai and retain completed counts when further lookups time out.
- Return fullscreen “More like this” posts to their shelf cards, including after swiping to an offscreen related post.
- Keep liked posts visible until their “More like this” recommendations are dismissed, including with Hide watched, Hide liked, and Hide saved enabled or when liking from fullscreen.
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
- Remove annotations in parentheses, square brackets, and curly braces from nhentai titles.
- Preserve For You history when clearing ordinary searches.
- Count only successful searches in lifetime statistics.
- Keep successful provider results visible when another source fails during pagination.
- Improve download recovery after app restarts, including interrupted animation exports, without duplicating uncertain transfers.
- Improve offline-save recovery after app restarts, including newly queued offline posts.
- Make profile deletion recoverable after interruption.
- Retain Like changes across navigation with Retry feedback when needed.
- Retain preference changes across navigation with Retry feedback when needed.
- Keep Automatic Codex tags in a compact scrollable list.
