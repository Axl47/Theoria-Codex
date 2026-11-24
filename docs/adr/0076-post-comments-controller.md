# 0076. Viewer comments prefetch only the first page, keep loaded lists, and focus a linked comment with a page cap

Status: Accepted

## Context
Swiping back to a post in the Viewer should show its comments at once, but comment requests compete with the shown media for the network. A link can also point at a comment deep in a thread.

## Decision
- `PostCommentsController` owns comment state for one Viewer session. The first page of each shown post (current and neighbours) loads in the background through `beforeFirstFetch`, which lets the Viewer keep the media's own loading ahead of this speculative request; later pages wait for a tap.
- A first page is requested when the source lists comments and the listing did not say there are none (`shouldFetchFirstPage`). A listing that says nothing is still asked.
- Reply lists stay in `PostCommentsState.replies` after collapsing so reopening is instant. The section is hidden (`hasSection`) only while the top-level list has loaded with no comments, so an empty later page leaves an already populated section visible. A failed page keeps what already loaded and the next token for retry.
- `focus` pages the post's comments, then the parent's replies, each capped at `MAX_FOCUS_PAGES` requests. On success `focusKey` names the item; the sheet scrolls to it and clears it with `focusShown` so it acts once. If the comment is not found within the cap or a page fails, no `focusKey` is set, but pages already fetched stay appended and a failed page marks its list failed. It also waits for `beforeFirstFetch`.
- `CommentsLoader` keeps the newest `CACHE_SIZE` pages; overlapping requests for one page share a single provider call via `SharedFlights`; a failure is passed on and never cached. A request that finished while another was starting has already filled the cache, so it is re-read before calling the adapter.
- On a comment row, holding the avatar or name opens the actions too (a tap already opens the profile), and a short delay before the hold fill starts keeps a scroll's touch-down from flashing it.

## Consequences
Comments never delay the shown media. A link to a comment beyond the cap opens the sheet normally.

## Applies to
- `app-logic/src/main/kotlin/com/theoriacodex/app/comments/CommentsLoading.kt`: `shouldFetchFirstPage`
- `app-logic/src/main/kotlin/com/theoriacodex/app/comments/PostCommentsController.kt`: `PostCommentsController`, `PostCommentsActions.focusShown`, `MAX_FOCUS_PAGES`
- `app-logic/src/main/kotlin/com/theoriacodex/app/comments/PostCommentsState.kt`: `CommentList`, `PostCommentsState`, `focusKey`, `hasSection`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/comments/CommentsLoader.kt`: `CommentsLoader`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/comments/CommentsSection.kt`: `CommentRow`, `commentRowHighlight`

## Guarded by

- `app-logic/src/test/kotlin/com/theoriacodex/app/comments/PostCommentsControllerTest.kt`: `the current post's first page loads once and swiping back asks for nothing (ADR 0076)`, `the background request waits for the gate (ADR 0076)`, `more comments continue from the page token and a failure retries the same page (ADR 0076)`, `nothing loads for a source without comments or a listing that says none (ADR 0076)`, `replies load on first opening, stay loaded when closed, and page on their own (ADR 0076)`, `focusing a top-level comment pages until it appears and then acts once (ADR 0076)`, `focusing a reply opens its parent's replies and pages them (ADR 0076)`, `a comment that is not there leaves the post's comments as they were (ADR 0076)`, `the search stops after a bounded number of pages and after a failed one (ADR 0076)`, `a reply search on a later page stops at the cap and keeps every page it loaded (ADR 0076)`, `a page that fails after others loaded keeps them and sets no focus (ADR 0076)`, `focusing waits for the gate like the background request (ADR 0076)`
- `app-logic/src/test/kotlin/com/theoriacodex/app/comments/PostCommentsStateTest.kt`: `an empty first page leaves no section while loading and failures keep one (ADR 0076)`, `pages append without repeating a comment and a failure keeps what loaded (ADR 0076)`, `an empty later page leaves a populated section and its comments (ADR 0076)`
- `feed-kit/src/test/kotlin/com/theoriacodex/app/comments/CommentsLoaderTest.kt`: `supports follows the adapter and a repeated page is served from the session cache (ADR 0076)`, `overlapping requests for one page share a single provider call (ADR 0076)`, `a request that missed the cache while another filled the page reads the filled page (ADR 0076)`, `a failure reaches the caller and is not remembered (ADR 0076)`, `the cache keeps only the newest pages (ADR 0076)`
- Left to prose: the hold gesture on a comment row's avatar and name, and the delay before its fill, are unguarded because they are touch feel in the Viewer's comment sheet. The Viewer's neighbour scheduling and the media gate passed as `beforeFirstFetch` are wired in `:feature-viewer` and are unguarded.
