# 0182. JobScheduler jobs have fixed, never reused IDs declared in one enum

Status: Accepted

## Context

Android's `JobScheduler` identifies a job by an ID that is unique per app. Scheduling a job replaces any pending or running job with the same ID, whatever service it names. Persisted jobs keep their IDs across app updates, and scripts that drive jobs refer to them by number.

## Decision

- `AppJob` lists every job the app schedules with its ID and service; `builder` creates the `JobInfo.Builder` with both, and callers add their own conditions.
- An existing ID is never renumbered or reused. A new job takes the next free number in `AppJob`.
- Jobs that share a service (`USER_DOWNLOAD`, `AUTOMATIC_DOWNLOAD`) still have separate IDs, since the ID alone decides what a schedule replaces.

## Consequences

Updating the app cannot orphan or collide with a persisted job. Removing a job leaves its number retired.

## Applies to

- `app/src/main/java/com/theoriacodex/app/AppJob.kt`: `AppJob`, `builder`
