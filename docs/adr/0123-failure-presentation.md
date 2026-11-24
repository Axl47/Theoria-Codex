# 0123. Failure messages separate account problems, browser challenges and offline from plain retries

Status: Accepted

## Context

Different failures have different fixes, and telling the user the wrong one wastes their time.

## Decision

- `requiresAccount` marks failures only an account change fixes; `retriesOnReconnect` marks failures a returning network could fix, excluding rate limits and sign-ins.
- `challengedSource` finds the first failed source behind a browser check, which verifying fixes and retrying cannot.
- Offline, one chip replaces every source a returning network could fix.
- `FeedFailureTile` says offline instead of the message while a retry could help, never for an account problem, and every feed retries by itself when the network returns.

## Consequences

New failure reasons must be classified into one of these groups.

## Applies to

- `feed-kit/src/main/kotlin/com/theoriacodex/app/search/SourceFailureMessages.kt`: `requiresAccount`, `retriesOnReconnect`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/search/SourceStatusLabels.kt`: `challengedSource`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/search/SourceStatusRow.kt`: `StatusChips`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/ui/components/FeedFailureTile.kt`: `FeedFailureTile`
