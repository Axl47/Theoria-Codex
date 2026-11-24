# 0040. Host-test Compose waits drain the main looper

Status: Accepted

## Context

Under Robolectric, `ComposeTestRule.waitUntil` advances Compose frames but does not run work posted back to the main looper once the screen is idle. A condition that reads a plain variable or repository, rather than the Compose tree (whose finders idle first), can then sit unsatisfied until the timeout while the answer waits in the looper. Tests failed or slowed intermittently this way.

## Decision

- Host tests (`*/src/test`) wait with `waitUntilSettled`, which calls `waitForIdle` on every poll. A bare `waitUntil` fails `scripts/check_architecture.py`.
- `waitUntilWithoutIdling` exists for screens where idling must not happen, and only the files in `NON_IDLING_WAIT_FILES` may use it:
  - `ViewerUgoiraReadyTest`: a playing ugoira loops forever, so Compose never goes idle.
  - `OffMainComputationTest`: the wait observes a worker thread parked on purpose; idling would wait on that worker.
- Adding a file to that list is a reviewed change to the script and to this record.

## Consequences

New host tests are correct by default when they use the helper, and the check names the helper when they don't. A screen that never settles fails inside `waitForIdle` rather than at the condition's timeout. Instrumented tests (`src/androidTest`) are not covered; journeys there use `AppJourneyFixture.waitFor`. A project compose rule that overrides `waitUntil` would make the plain call correct and remove the per-call helper.

## Applies to

- `ui-kit/src/testFixtures/kotlin/com/theoriacodex/app/testing/ComposeWaitTestSupport.kt`: `waitUntilSettled`, `waitUntilWithoutIdling`
- `scripts/check_architecture.py`: `host_tests_wait_settled`, `NON_IDLING_WAIT_FILES`
