# Changelog

## [Unreleased]

### Changed

- The Viewer's rotate button is always available with the controls, so you can turn the screen by hand on any post.
- The Viewer's post strip fills the width with more thumbnails in landscape instead of seven widely spaced ones.
- The search tray under a post's tags, and in Codex tag search, folds to one line beside Search. It starts folded in landscape; tap it to edit the search.

### Fixed

- The Viewer's rotate button no longer floats high above a landscape video's timeline or a multi-page post's page bar.

## [1.6.0] - 2026-11-12

### Added

- A comment button in the Viewer opens a post's comments in the info sheet, for Pixiv, nhentai, Danbooru-family and Gelbooru-family sites.
- Holding a comment opens its actions.
- Tapping a comment's author opens their account page.
- Account pages for Gelbooru-family sites, Danbooru, Pixiv and Iwara show the profile details and the account's feeds on tabs.
- The Viewer rotates to fit a video when it opens. A rotate button flashes on new posts.
- Brightness control now works for all media, and holding the screen scrubs a video's timeline.

### Changed

- Navigation is more responsive in landscape.
- Gelbooru-family accounts load favorites with one search per page, so they load faster.
- Typing an nhentai ID on Search opens that post right away, before it is added as a tag.
- Custom booru sites now show a post's smaller sample image first and then the original, like the built-in boorus.
- Gallery pages show their thumbnail while the full page loads, for every source.
- The Viewer now prepares the previous post as well as the next one, so swiping back starts sooner.
- Pixiv animations next to the one you are viewing now download ahead of time when you are not on mobile data.
- Brief network errors and server hiccups are retried automatically, on Custom Sites too.
- Opening a post while its card is still loading details reuses that request instead of sending a second one.
- Cards no longer download a duplicate copy of an nhentai or Hitomi image as if it were a sharper version.

### Fixed

- Swiping back to a finished video now restarts it from the beginning, instead of ending it again and skipping past it.
- Opening a creator page keeps the Viewer open beneath it, and a creator-feed Viewer closes when you open another creator.
- Quick swipes in the Viewer no longer bounce back on the first try after landing on a new post.
- Open in browser now opens your browser instead of reopening the post in the app.
- Hitomi video cards now autoplay in search results.
- Iwara video cards now autoplay once their details load, in search, For You and creator pages.
- A post whose site answers with a different post now shows an error, instead of loading forever.

## [1.5.0] - 2026-11-10

### Changed

- When a link leads you to set up a site, that post now opens once the site is added, instead of the link being forgotten.
- The message for rejected API keys now mentions keys limited by IP address or permissions.

### Fixed

- The update prompt now shows the new version, its download size and its release date, instead of the word "unknown".
- A site's API key is now tried first when you add, re-test or browser-check it, so a valid key works even where visitors without an account are refused.
- A wrong API key is reported when you enter it, instead of being saved without being tested.
- Saving a new API key lets the site's requests through again right away, instead of waiting out a "site is down" mark.
- Adding a site back after removing it no longer leaves it marked as down for up to 10 minutes.
- A site with a saved key is no longer marked as needing verification by the check that runs at launch.
- A shared post whose files a site withholds from your account now says so, instead of "post was not found".
- Error text now points to Settings > Sites, instead of an Accounts page that no longer exists.

### Added

- Share or download your Codex stats as a file across any of your profiles, leaving out any profile, Codex or post first, and the totals are recalculated to match.
- Compare your Codex stats with a file a friend sent you, including top posts you can open in the Viewer.
- Shared stats now include your overall time in the app, app opens, posts watched and saved, and searches, which you can leave out, and the comparison shows them side by side.
- Top posts in a stats comparison now show thumbnails.
- Settings has a Diagnostics report that shares or downloads recent crashes and errors as a file, with web addresses and account details removed.
- Posts in a Codex's Top posts list in Stats now open in the Viewer when you tap them.
- When a site refuses your account in search, a re-test or a shared link, a button takes you straight to that site's API key field.
- A site that needed a browser check shows its API key fields before you add it.
- Images and videos that a Danbooru-type site refuses are retried once with your API key, on that site's own addresses only.

## [1.4.0] - 2026-11-09

### Added

- In the Viewer, drag the page bar of a multi-page post to move page by page, with a floating preview of the target page and its neighbours.
- Multi-page posts show stacked cards in the Viewer's post strip, so you can spot them before opening them.
- The open post unfolds its pages in the Viewer's strip, scrolling between the previous and next posts so both stay one tap away.
- Danbooru is now a search source, with artists available as creators you can follow.
- AIBooru is now available as a search source.
- Danbooru and AIBooru share one "Booru" entry in the source picker, like the R34 sites, so the picker stays compact.
- The search editor warns when Danbooru will only get the first two tags of a Unified search, since guests can search at most two tags there.
- A Danbooru-only search with more tags than Danbooru allows is stopped with an explanation instead of failing at the provider; ranked sorts allow one tag, because the sort uses the other.
- For You learns across Pixiv, Danbooru, AIBooru, Gelbooru and Rule34.xxx: Likes on one source now suggest the same interests on the others, even sources you haven't liked anything on yet.
- Add your own booru in Settings → Sites by typing its address. Theoria checks whether it is a Danbooru or Gelbooru 0.2 site and adds it as a source you can search, like and save from.
- Sites that need an API key ask for one when you add them. Keys stay encrypted on your device and are never included in backups.
- New sites stay out of Unified search and For You until you turn that on for the site.
- Each site can be re-tested, renamed, given new keys, or given its own User-Agent and Referer.
- Removing a site keeps its likes, Codex items and history; they open again when you add the site back.
- A site behind a browser challenge can be verified in the app: pass its check and the page closes by itself once Theoria gets through, and the source works right away.
- Verify in browser tells you when a site still refuses Theoria after its check, instead of failing later.
- A site whose re-test is blocked by a challenge offers Verify in browser on its row.
- Removing a site also removes the browser cookies Theoria held for it.
- Search now has saved searches alongside saved tags. Save the search on screen and run it again with a tap.
- Hold a tag in a post's tag sheet to save or unsave it.
- Like a post from the Viewer's info sheet without closing it.
- Share a post link from an added site to open it in Theoria.
- Importing a Codex with posts from a site you haven't added offers to set that site up.

### Changed

- Unified search shows results as soon as the first sources answer; slower sources add their posts below without moving what is already on screen.
- Reopening the app shows your last search right away while it refreshes.
- Hitomi cards, and Iwara cards with a custom thumbnail, now load small thumbnails instead of full-size images.
- Choosing a page in a Hitomi gallery loads small page thumbnails instead of full pages.
- Feeds start loading the next card images before you reach them when you are on an unmetered network.
- rule34.xxx posts show a lighter sample in the Viewer first, then the original.
- Gelbooru's untagged feed loads several times faster, including every further page.
- The Viewer's page bar now fills up to the current page, like video progress.
- On nhentai, a post's page gallery fills in right away with small previews, and each page sharpens once it stays on screen.
- Collections saved for offline use now stay available in the released app, and copies saved before this update are recovered when the app starts.
- Post cards show their like, count, duration, time and source as plain marks over soft edge shading instead of separate badges, so more of each post stays visible.
- Galleries you have partly viewed show how far you got as a thin line along the bottom of the card.
- Liking a post from a card now gives the same haptic tick, heart pop and pink heart as liking it in the Viewer.
- Recents' All view now groups each day's searches in one row at the top of that day, above the posts you viewed.
- Searches in Recents read like the search editor: "or" alternatives are muted and excluded tags are red, with the source and time beside each search.
- Pixiv tags show their English name beside the Japanese tag on one chip, and tapping it searches the Japanese tag.
- Pixiv tag translations are always shown in English, whatever language your phone uses.
- Feed cards load a source's preview-size images instead of originals, so feeds fill in faster and use less data.
- On a metered network, the Viewer preloads only the next and previous videos, and only their opening seconds, to save mobile data.
- Theoria keeps more already-seen images on your device (up to 512 MB), so scrolling back reloads them without downloading again.
- Saving a post offline reuses the copy already loaded on your device instead of downloading it again, including videos you have watched through.
- Scrolling feeds, liking posts and typing in Search do less redrawing, so they stay smoother on busy screens.
- Video, GIF and animation timelines in the Viewer redraw their progress without rebuilding the controls on every frame.
- The Viewer keeps fewer pages decoded in memory ahead of you and swipes through animated pages with less memory pressure.
- Pixiv animations fetch their frame details once and share them between the card and the Viewer.
- Settings statistics now update only while you're looking at them, instead of in the background.
- The Codex library and its covers open faster on large collections.
- Opening a video in the Viewer stops the feed cards behind it, so they no longer keep downloading while hidden and the Viewer starts playing sooner.
- A clip opened from the feed is kept on your device as it plays, so looping it does not download it again.
- Images already on your device show straight away instead of first checking back with the site, so gallery pages Theoria preloaded appear without a delay.
- The next gallery page is prepared before you turn to it, so it appears immediately.
- Preloading pages further ahead uses less processing and memory.
- Opening a post loads what you tapped first, before preloading the pages and posts around it.
- Opening a video from a feed card starts playing sooner, from what the card had already downloaded instead of fetching it again.
- Swiping to the next video starts from what Theoria preloaded even while that preload is still running.
- The first scrub preview appears sooner when you start dragging a video's timeline.
- Videos next to the one you're watching preload only their opening seconds on every network, so watching one video no longer downloads up to about 48 MB of videos you may never open.
- Seeking a video past what it has loaded, and letting go after scrubbing, resume playback sooner: the abandoned download is dropped at once instead of being read out first.
- Scrub previews appear sooner when you drag a video's timeline, most of all on a second drag, and stop downloading as soon as you let go.
- Letting go after scrubbing a video starts playback on the frame the preview showed, and much sooner when you scrub to a part of the video that hasn't loaded yet.
- Videos in the Viewer start, and resume after a seek, once half a second has loaded instead of a full second. A video that has already had to stop and load waits for the full second again, so a slow connection doesn't stall more often.
- Sites proven to work since you opened the app show a check mark on their row in Settings → Sites and beside Verify in browser, replacing the "Working" text.
- A site's API keys sit behind a collapsed "API key" item on its page, which opens to show their options.
- Invert scroll direction moved into Settings → Controls, the renamed Classic controls sheet, with more room between its options.
- Accounts and Sites are now one Settings → Sites page, listing built-in and added sources with each one's account and browser check.
- Built-in sources behind a browser challenge, like Iwara, can now be verified in the app; tapping a challenged source opens its check.
- Settings pages show their full path in the title, such as Settings / Sites / Safebooru.
- Test all in Settings → Sites checks every source with a real search, naming each one as it goes and marking the result on its row.
- Unified search says when a site is down for maintenance, so you know the results are missing it. Dismiss the notice and it stays away until that outage ends.
- Unified search says when a site needs browser verification, with Verify in browser and Dismiss.
- In Settings → Sites, Test all sits in line with the Built-in header.
- The first search after opening the app starts faster on Wi-Fi: Theoria reconnects to the sites you used last while the app comes up.
- Networks whose IPv6 doesn't really work no longer slow down new connections once Theoria has noticed.
- Source menus show a red dot on sources that are down or need verification.
- The save picker suggests Codices you just saved to from the same search.
- Fullscreen brightness and volume carry over between videos for the rest of your session.
- The creator's name in the Viewer's info sheet is now a link to their page.
- For You uses a sparkle icon, matching For You searches in Recents.

### Fixed

- On networks with broken IPv6, sources no longer stall for up to 30 seconds or fail to connect, and one slow source no longer blanks a whole Unified search.
- Sources no longer all start marked as "can't be found" after a brief network hiccup; a site is only marked missing once separate lookups have failed.
- R34V videos play again in feeds and the Viewer; they previously failed to load on every Android device.
- Videos are requested with the same User-Agent as the site's images, including a custom site's own User-Agent, so sites that check it serve their videos too.
- A R34V or R34G search with excluded tags now shows its results when one post's details can't be read, instead of failing the whole page.
- Switching source in Search now shows that source's last search, so the results and the filter button always match the source you picked.
- Opening an nhentai or Hitomi gallery in the Viewer keeps its cover on screen until the first page loads, instead of flashing to black.
- When a post's full image fails to load in the Viewer, its preview now takes its place instead of spinning indefinitely.
- Typing a tag's English name in Pixiv search and pressing Enter now searches the matching Japanese tag instead of the English text.
- Pixiv searches keep tags that include parentheses, such as ラム(リゼロ), instead of cutting them short.
- Gelbooru and Rule34.xxx tags with apostrophes or ampersands, such as jojo's_bizarre_adventure, now display correctly and find their posts.
- Paheal cards show their images again, with or without tags.
- Rule34Gen cards show their thumbnails when browsing without tags.
- Rule34Gen and Rule34Video searches show every card's thumbnail, not just the first few.
- nhentai pages that stall partway through loading recover in a few seconds instead of hanging for up to half a minute.
- When a site is down, searches fail quickly instead of waiting about a minute.

## [1.3.0] - 2026-11-06

### Added

- Share a provider link to Theoria from any app to open it here.
- Settings > Sources has a shortcut to let provider links open in Theoria directly, and shows which sites are on.
- Search and For You now retry failed feeds on their own when your connection comes back.
- Back up daily to a folder you choose, under Settings > Storage & app. Theoria keeps the last seven and tells you if one fails.
- Backups now include your statistics when you include Recents.
- Undo right after deleting a Codex or clearing Likes.
- Your favorite tags now appear in the search editor's Saved section with a star.
- Tap a saved tag to add it, again to exclude it, and once more to clear it; the chip shows which applies.
- Saved tags matching what you type appear first in the suggestions, marked with a star.
- Tap a tag in the search editor and choose Save tag to add it to your saved tags, or remove it from them if it is already saved.
- Save an "or" group as a saved tag from any of its tags' menu; tapping the saved group adds the whole group to your search, and tapping it again removes it.
- Give a saved "or" group a name when you save it, or later from Manage in the Saved section; the chip shows the name instead of its tags.
- Liking a post in Recents or in the Followed and Searches collections now opens its related posts, as it does in Search, For You and creator pages.
- Hold a post and choose More like this to open its related posts without liking it, in Search, For You, Recents, creator pages and the Followed and Searches collections.

### Changed

- Manage saved tags from the Manage action in the Saved section, which replaces Favorites in the search editor's overflow menu.
- The Favorite tags sheet is now called Saved tags.
- Feeds now say you're offline instead of blaming each source when the connection is the problem.
- On a metered network, feed cards now autoplay a smaller video stream when the source offers one.

### Fixed

- Search Reset is available immediately for restored tags, without needing to edit a tag first.
- Opening a video on a metered network or with Data saver no longer downloads a larger version in advance that the Viewer then doesn't play.
- Restoring a backup now adds what's missing to your existing profiles and collections, and your profiles keep their names. Restoring the same backup again no longer creates duplicates.
- Retrying a search that timed out now repeats that search instead of the one before it.
- For You now shows sources that need an account and opens Source accounts from them, as Search does.

## [1.2.0] - 2026-11-04

### Added

- Sort the Codex Library and the library order in Add to Codex by Most watched or Most opened, using the time and open counts shown in Stats.
- Removing a tag from the search editor offers a brief Undo.
- Post info now shows your search under the tags and can run it from there, so you no longer have to go to Search first.
- Add a post's tag to an "or" group from post info with "+ or"; tags of another type dim while a group is the target.
- Exclude tags and build "or" groups in a Codex's "Search with these tags".
- Filter a Codex's tags while building a search, and switch source in place without an extra menu.

### Changed

- Recents sections (All, Watched, Codex, Searches) moved from a row of chips into a dropdown beside the source selector, leaving more of the screen for your history.
- The search editor now shows your tags as one readable query, in the order you added them, with "or" groups inline and excluded tags below.
- Add an alternative right on its "or" group; the mode and target rows are gone, and the button beside the field shows how the next tag joins.
- Tapping a tag opens its actions right next to it.
- The tag field sits just above the keyboard, and your query stays visible above the suggestions while you type.
- Search is now at the top of the editor, with Favorites, Undo last search, and Reset in its overflow menu.
- Post info tags now wrap with their full names instead of being cut off in columns. Tap a tag to add it, again to exclude it, and once more to clear it.
- Artists, series, and characters now come before general tags in post info, and very long tag groups collapse behind "+N more".
- Tag counts are shortened, such as 84k or 1.2m.
- The Viewer's post info uses a compact action row, so tags start near the top. Go to Search and Open in browser are under More.
- Randomize in a Codex's search is now Draw, which takes up to five tags from one saved post so they are known to appear together.
- A Codex's search now keeps each tag's type, so an artist is searched as an artist.

### Fixed

- Moving to another post or image in the Viewer no longer brings back the header and footer; they stay hidden until you tap.
- Zooming in the Viewer, by pinching or double-tapping, no longer brings back the header and footer.
- The search bar over results now reads "mountain or snow" for an "or" group instead of listing the tags as if both were required.
- When an "or" group's tag type rules out suggestions, the editor now says so instead of showing nothing.
- Adding a typed tag from another source's post now warns you before it switches Search to that source and removes your other typed tags.

## [1.1.0] - 2026-11-03

### Added

- Sort the Codex Library by name, date added, or item count from Reorder mode; switching back to Manual restores your own arrangement.
- Use the Codex Library sort as the non-suggested order in Add to Codex.
- Rename a recommendation profile from its Settings menu, keeping its likes, Codices, and hidden tags.
- Pull down on the Viewer to close it; the media follows your finger, then lands back on its card.
- Swipe up on the Viewer to open post information.
- Hold a video, GIF, or ugoira to play it at 2× until you let go; long press still opens information on other media.
- Liking a post in the Viewer gives a small heart pop and a haptic tick.
- Pull down to refresh Search results and For You.
- Long-press and drag across a Codex in edit mode to select a whole range of posts.
- Tap the current tab again to scroll back to the top.
- Show a Play icon on animated posts in the Viewer's thumbnail strip and recommendation drawer.

### Changed

- With a duration filter on, posts now keep appearing while you scroll, and the next page loads sooner instead of waiting for every duration on the current one.
- With a duration filter on, posts appear in feed order, and a post that takes too long joins the bottom of the list instead of shifting everything above you.
- Duration lookups read far less data per video and run several at a time.
- Pixiv ugoira now show up under a duration filter instead of always being hidden.
- Redesign the full-screen Viewer around the artwork: controls now float on soft shadows instead of solid bars, on images, animated media, and video alike.
- Tint the seek bar and selected thumbnail with a vivid color from the current artwork, such as hair, clothes or background, falling back to white for monochrome art.
- Give the seek bar the full screen width in portrait for finer scrubbing; it thickens and shows a handle while you drag.
- Show sharp scrub previews in the video's real shape, with a large time and how far you have moved, while other controls fade away.
- Show brightness and volume on a readable rail on the side you are swiping, instead of a tiny label at the top.
- Show double-tap seeking with a soft edge shadow and chevrons pointing the way you jumped.
- Double-tap seeking no longer opens the Viewer controls.
- New recommendations now add to the Viewer's recommendation drawer instead of replacing what was there.
- Show the page position of multi-page image posts as a segmented bar above the thumbnails.
- Animated WebP now uses the same progress bar as other media, including the thin line while controls are hidden.
- Show the playback speed beside the time when it is not 1×.
- Posts with video open more smoothly, and video cards take much less work to appear in feeds.
- With controls showing, swipes and taps just above a video's timeline now work, instead of that strip of the screen ignoring them.
- Show every Codex in the Library as a cover tile with its name and count on the cover, matching the Saved collections; Codex actions now open by long press.
- Separate Saved from the other Codices in the Library with a thin line.
- Choose the source in For You and Recents from the same icon menu as Search.
- Mark watched posts in Search and For You with an eye icon instead of a time label that looked like an upload date.
- Opening a creator now slides in quickly and Back plays the exact reverse, replacing a slow crossfade.
- A Codex now grows out of its Library tile and shrinks back into it.
- Reordering Codices and For You sections glides neighbouring rows, settles the dropped row, ticks at each position, and scrolls long lists at their edges.
- Tapping a tab slides to it instead of jumping.
- Slow swipes between the main tabs commit at about 16% of the screen, like the Viewer, instead of snapping back before half.
- Viewer controls fade in and out, and Settings pages and For You views transition instead of cutting.
- System back in the Viewer now follows your swipe, shrinking the media before it returns to its card.
- On video, brightness and volume swipes use the outer columns of the screen, leaving the centre for closing and information.
- Viewer swipes keep your finger's speed when they land, and swiping during a landing continues to the next post instead of being ignored.
- A short, fast flick now changes post, a slow drag needs about 16% of the screen, and flicking back cancels the swipe.
- Video keeps playing while you swipe.
- Pages stretch and tick softly when there is nothing further to swipe to.
- Double-tap zooms at the tapped point with a smooth animation, and pinching now zooms around your fingers.

### Fixed

- Zoomed gallery pages can now pan to their edges when they are a different shape from the first page.
- Browsing many Codices no longer keeps growing memory use.
- Feeds no longer reshuffle into different columns after closing the Viewer or when scrolling back up.
- Gelbooru videos no longer disappear from a duration filter for five minutes when the host is briefly overloaded.
- Searching with a Codex's tags now always updates Search's tags and runs that search, instead of sometimes repeating the previous one when Search had not opened yet.
- Smooth the Viewer's closing animation by waiting to start feed videos until the media lands on its card.
- Remove the navy band behind the status bar above the Viewer's black background.
- Double-tap to zoom a still image anywhere on screen, not only in its middle.
- Keep zoomed media on screen instead of letting it be dragged out of view.
- Return a post to its card in Recents' All view, instead of playing the fallback animation for posts on screen.
- Show the Codex Library immediately when leaving a Codex, instead of a blank screen while it reloads.
- Stop a Codex briefly saying it no longer exists, or that it is empty, while it opens.
- Show a Codex and the Saved screen immediately when returning to them from the Viewer.
- Open the Viewer and Codices without waiting for settings and likes to reload.
- Open Add to Codex with your Codices already listed, instead of briefly empty.
- Stop the whole app redrawing whenever a post opens, which stalled the opening animation.
- Stop feed videos going blank behind the Viewer when you tap a post.
- Open large Codices like Likes with their posts already showing, instead of an empty screen after the opening animation.
- Stop rapid flicks in the Viewer being dropped when one lands while the previous post is still settling.
- Stop the Viewer dipping to black when the full image replaces its preview, on opening a post and while swiping.
- Keep a loading thumbnail or video inside the media's own shape instead of briefly filling the screen.

## [1.0.1] - 2026-11-02

### Fixed

- Refresh storage usage when Settings opens so Storage & caching shows totals before Manage storage is opened.
- Keep Search's scroll position current with the Animated filter enabled, so returning to Search does not jump to an older position.
- Keep Search's saved scroll position aligned when local filters change which posts are visible.
- Wait for the saved post's duration filter decision before restoring Search's scroll position.
- Show a GIF card's thumbnail immediately while the full GIF downloads, instead of leaving the card blank until the download finishes.
- Stop large GIFs from holding up other thumbnails, and stop downloading GIFs that are scrolled past before they are visible.
- Keep GIF cards animating when a static sample is also available, instead of replacing the animation with a still image.
- Let large GIFs finish loading in the Viewer on slow connections instead of restarting after 20 seconds.
- Show a thumbnail instead of the animated GIF on collection covers, so covers load faster.
- Keep the Viewer on the current post when a swipe is abandoned, instead of bouncing to the previous post and getting stuck on its thumbnail.

## [1.0.0] - 2026-11-02

### Fixed

- Keep committed search input cleared when autocomplete finishes late.

- Restore the previous For You sort when Undo is tapped before a filter reset finishes loading.

- Keep Recents days distinct across years.
- Clear the Auto-save alternative target when removing a tag group to avoid editing the wrong rule.

- Restore language and Full color filters when undoing Clear filters.
- Exclude retained download history from storage usage totals and label it separately.

### Changed

- Start loading fullscreen swipe destinations and buffering their media during the drag, showing thumbnails and waiting until the transition settles to play.

- Keep up to 400 days of local activity history for Stats trends while preserving existing lifetime totals.
- Keep private thumbnails for posts in Codex statistics top lists, even after cache clearing or removing a like.

- Choose Codex actions from a labeled, grouped menu, with automatic tag rules moved into their own Auto-save sheet that shows source icons and reveals the tag list only while adding a tag.
- Choose post actions from the same labeled long-press menu, led by saving, with search terms moved into their own sheet and Remove from Codex set apart at the end.
- Search from a one-line bar that opens a full-screen editor showing every tag, grouped by type on sources like Hitomi, with Must have, Any of, and Without always on screen.
- Tap any search tag to choose what happens to it, instead of excluded tags being removed immediately.
- Use labeled menus for viewer Info, download actions, and the Codex search source picker, and remove redundant Cancel and Done buttons from related sheets.
- See active filters on a filter button that names them, shows their number when there are more than two or while scrolling, and clears them in one tap with Undo.
- Use one filter sheet layout across Search, For You, Codices, and creators. Search filters now apply together when the sheet closes.
- Filter Search by more date ranges, including custom dates, on every source using each post's date.
- Open Likes, Followed, and Searches directly from their own covers in the Codex library, with See all and actions visible on the Saved screen.
- Reach any For You interest from jump chips under a larger Your mix shelf, with each shelf's Pin, Save to Codex, and Dismiss in its own menu, and New search and Reorder in the header menu.
- Open Recents on a day-by-day timeline of posts and searches, with evenly sized post shelves, For You searches filtered inside Searches, a visible pin button, and clear actions that name what they clear.
- Say Save and Saved instead of Pin across Recents and For You, with a bookmark icon.
- Rerun a saved search in one tap from the Saved row in the search editor.
- Hold Likes, Followed, or Searches in the Codex library to open their menus, as on the Saved screen.
- Read your whole setup at a glance on a redesigned Settings screen, where each row shows its current value and opens a sheet or page.
- See Stats on their own screen, with time split by activity, 7-day, 30-day, and yearly trends, jump chips, and a ranked list of Codices with their top posts.

### Removed

- Remove the minimum score filter. Older saved searches keep working and ignore any score they stored.

### Fixed

- Android Back buttons and gestures now play the Codex exit animation, including Saved Sub-Codices.

- Slide Codex screens from left to right when going back, with the previous screen entering from the left.
- Try another available video decoder when the preferred decoder cannot start, allowing feed previews and fullscreen playback to recover from hardware decoder resource exhaustion.

## [0.12.0] - 2026-11-01

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

- Preserve the For You feed position and related-post shelf after closing fullscreen, and defer hidden overview previews while replaying a search.

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
