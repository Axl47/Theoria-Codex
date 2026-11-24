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
