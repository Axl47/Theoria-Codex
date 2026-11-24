# 0140. Search results report failures once, retry what a returning network or a passed browser check fixes, and apply filters on screen

Status: Accepted

## Context

A search can fail for one source, for several, or entirely, and the cause decides what helps: a returning network, a browser check that has since passed, or nothing the app can do. Filters and notices must also avoid redrawing every card for each keystroke or like.

## Decision

- A failed page continuation belongs beside its usable grid, with the exact page kept for Retry (`pageFailure`). The grid's footer counts a failed source as a failure because unified paging drops it, which would otherwise read as the end (`searchFooterMessage`); when filters hide every loaded post there is no grid and the empty state speaks instead.
- A multi-source search names missing sources and why (`SourceOutageNotices`); a single-source search skips these because its own error already says so and its action verifies or retries. Sources with a notice of their own are left out of both the generic message and its action. Each notice stays dismissed as long as its outage lasts. A challenge notice is always for one source (`verifiableSource`) because verification happens one site at a time.
- `retrySearchAfterReconnect` waits for any request begun offline to settle first, since it may still be failing and its outcome should get the retry too. It retries the whole search when the search itself failed, otherwise each source that `retriesOnReconnect` one at a time so each keeps its own continuation.
- `RetryVerifiedSources` loads a failed source again by itself once its browser check passes, here or anywhere else. It retries one at a time and only while the search is not asking anything else, because a retry is ignored while a request runs.
- Grid cards capture only `showSourceBadge` and read the current feed at tap time (`rememberUpdatedState`), because the whole state changes on every keystroke and page and the filtered list is rebuilt on every like; cards must not recompose for either.
- Visibility filters follow the search on screen, not the draft: the applied query's media decides the phone's still-post filter (`hidesStillsOnPhone`) as its date range does, and the Triage deck follows the applied query hash, so a draft that has not searched yet has nothing to triage. Picker values and local date filtering both read dates in UTC.
- Filters apply live as they change; closing the filter sheet settles the search they made. Applying server filters dispatches the nhentai full-color and language changes only when they really changed, since both rewrite the draft's nhentai terms.
- A refresh shows the pull indicator on the results; the progress line covers newer searches (the open composer shows its own) and refreshes of an emptied feed.

## Consequences

Users see one notice per outage instead of one per source chip, and offline failures recover without a manual retry. New failure kinds need a decision on whether they retry on reconnect and whether they are announced per source.

## Applies to

- `feature-search/src/main/kotlin/com/theoriacodex/app/search/SearchFailurePresentation.kt`: `pageFailure`
- `feature-search/src/main/kotlin/com/theoriacodex/app/search/SearchEmptyStatePolicy.kt`: `searchFooterMessage`
- `feature-search/src/main/kotlin/com/theoriacodex/app/search/SearchResultsBody.kt`: `RetryVerifiedSources`, the outage notice and grid-card wiring
- `feature-search/src/main/kotlin/com/theoriacodex/app/search/SearchRecovery.kt`: `retrySearchAfterReconnect`
- `feature-search/src/main/kotlin/com/theoriacodex/app/search/SourceFailureUiText.kt`: `SourceOutageNotice`, `verifiableSource`, `sourceOutageNotices`
- `feature-search/src/main/kotlin/com/theoriacodex/app/search/SearchStateComponents.kt`: `SourceOutageNotices`
- `feature-search/src/main/kotlin/com/theoriacodex/app/search/SearchFeed.kt`: `SearchFeed`
- `feature-search/src/main/kotlin/com/theoriacodex/app/search/SearchFilterControls.kt`: date range picker
- `feature-search/src/main/kotlin/com/theoriacodex/app/search/SearchScreen.kt`: `SearchFilterActions`, filter sheet dismissal
