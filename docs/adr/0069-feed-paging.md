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

## Guarded by

- `app-logic/src/test/kotlin/com/theoriacodex/app/feed/FeedPageDemandTest.kt`: `hidden first pages drain only three pages before an explicit continue (ADR 0069)`, `new scroll demand resets budget while layout only changes do not (ADR 0069)`, `repeated viewport callbacks cannot submit duplicate page requests (ADR 0069)`, `empty provider pages use settled generations even with no observed loading frame (ADR 0069)`, `restoration loads pages past the automatic budget until the anchor is present (ADR 0069)`, `restoration page failure pauses demand and exhaustion stops it (ADR 0069)`, `pending duration resolution and active loading consume no budget (ADR 0069)`
- `app-logic/src/test/kotlin/com/theoriacodex/app/feed/FeedResultsTest.kt`: `a later page refreshes shown posts, adds new ones and leaves out copies of shown files (ADR 0069)`
- `app-logic/src/test/kotlin/com/theoriacodex/app/feed/FeedCursorTest.kt`: `each source's token follows how it answered, and an unasked source keeps its own (ADR 0069)`, `a source whose first page failed keeps its null token, so its first page is asked again (ADR 0069)`
- `app-logic/src/test/kotlin/com/theoriacodex/app/media/DurationFilterReadinessTest.kt`: `a few remaining lookups do not hold back the next page (ADR 0069)`
