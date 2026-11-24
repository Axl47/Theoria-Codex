# 0099. Compose tests drain the main looper and step background work by hand

Status: Accepted

## Context

Under Robolectric, bare `waitUntil` advances Compose frames without running work posted back to the main looper once the screen is idle. When the condition reads a plain variable or repository instead of querying the Compose tree (whose finders call `waitForIdle`), background work returning through `Dispatchers.Main` can sit unread until the wait times out.

## Decision

- `waitUntilSettled` drains the main thread on each pass. It must not be used on screens that never go idle, such as looping animations with an advancing clock.
- `SteppedDispatcher` queues background work until `drain` runs it on the test thread, so frame tests decide when it lands. `drainNewestFirst` runs the queue newest first, as if earlier work were delayed.

## Consequences

Tests that need late-landing work choose the order explicitly rather than relying on timing.

## Applies to

- `ui-kit/src/testFixtures/kotlin/com/theoriacodex/app/testing/ComposeWaitTestSupport.kt`: `waitUntilSettled`
- `ui-kit/src/testFixtures/kotlin/com/theoriacodex/app/testing/SteppedDispatcher.kt`: `SteppedDispatcher`
