# 0069. Feeds drain pages within a bound, except to reach a saved place, and merge without reordering

Status: Accepted

## Context
Feeds load more pages when the viewport nears the end or local filters leave too few posts, but automatic draining must stop between user actions. Restoring a saved place needs a page that may be far down the list.

## Decision
- `FeedPageDemand` bounds automatic page draining (`automaticPageLimit`) independently of provider continuation and visible counts.
- A request is acknowledged only by its route owner's settled generation, which changes even when a provider page adds no canonical posts; repeated viewport callbacks cannot acknowledge it.
- A saved place is a known target: its pages load until it is present or the provider runs out, without the automatic page cap; failures, a refresh or paging in progress, and duration lookups that block paging still hold them back. Capping them would strand the restore on a grid that cannot show Continue without a scroll, and a scroll supersedes the restore.
- `mergeFeedPosts` keeps accepted order, admits new canonical IDs and fresher payloads for existing IDs, and leaves out a new ID whose file the feed already shows from another site, so the first copy keeps its place.
- `FeedCursor` admits a request for any source in `pageTokens`; a null token retries the first page after a root failure. A source that answered keeps its next token or leaves at its end, an excluded one leaves, a failed one keeps its token.
- `DurationFilterReadiness.blocksPaging` (read by `FeedPageDemand` as `durationsBlockPaging`) lets the next page load while a few duration lookups remain (`DURATION_PAGING_ALLOWANCE`), so scrolling meets posts instead of a wait.

## Consequences
Search and For You page the same way. Changing the page limit must keep the restore exception to the cap.

## Applies to
- `app-logic/src/main/kotlin/com/theoriacodex/app/feed/FeedPageDemand.kt`: `FeedPageDemand`
- `app-logic/src/main/kotlin/com/theoriacodex/app/feed/FeedResults.kt`: `mergeFeedPosts`
- `app-logic/src/main/kotlin/com/theoriacodex/app/feed/FeedCursor.kt`: `FeedCursor`, `accept`
- `app-logic/src/main/kotlin/com/theoriacodex/app/media/DurationFilterReadiness.kt`: `blocksPaging`
