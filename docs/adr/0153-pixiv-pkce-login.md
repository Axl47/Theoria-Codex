# 0153. Pixiv login persists its one-shot PKCE session before the browser opens and commits the callback uncancellably

Status: Accepted

## Context

Pixiv signs in through the system browser and a callback, so the app can be restarted or a second controller instance can handle the callback. Pixiv's final native handoff may carry only a short-lived code.

## Decision

- The browser is launched only after the exact callback state and verifier are durably encrypted (`writeVerified`, which atomically replaces any older attempt and verifies the committed value), so a later controller instance can finish the same one-shot session.
- Only Pixiv's exact native handoff (`pixiv://account/login`) may omit `state`; similarly prefixed paths are not callbacks. PKCE still binds that code to the persisted authorization attempt.
- Cancellation is retryable until the `ensureActive` check before the terminal commit. Beyond it, saving the tokens and consuming the session run under `NonCancellable`, so ambient cancellation cannot report an indeterminate failure after either durable write; a `CancellationException` thrown by the persistence boundary itself still escapes unchanged and leaves the session available for a retry.
- `consume` removes only the exact session that reached a successful terminal credential save.
- Controller operations share one process-wide mutex.

## Consequences

A callback handled twice cannot consume another attempt's session. Changing the callback shapes requires updating `pixivCallbackKind` and keeping the state requirement for the app callback.

## Applies to

- `feature-settings/src/main/kotlin/com/theoriacodex/app/sourceauth/PixivPkceController.kt`: `PixivPkceController`, `startAuthorizationUri`, `handleAuthorizationCallback`
- `feature-settings/src/main/kotlin/com/theoriacodex/app/sourceauth/PixivCallbackKind.kt`: `pixivCallbackKind`
- `feature-settings/src/main/kotlin/com/theoriacodex/app/sourceauth/PixivPkceSessionStore.kt`: `writeVerified`, `consume`
