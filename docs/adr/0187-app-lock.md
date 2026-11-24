# 0187. The app lock gates the whole app behind a device-owner check and fails open only without a screen lock

Status: Accepted

## Context

The lock protects an app that can show private content. It must not lock its owner out, must not leave content readable behind the prompt, and must not be switched by a backup. Picture-in-picture keeps playing while the app is not in front.

## Decision

- `AppLockSession` tracks whether this run has passed the lock. Time away starts at pause and ends at resume; more than `APP_LOCK_GRACE_MS` (60 s) away locks again. Picture-in-picture counts as away, since it keeps playing but must not hand back the whole app later.
- `AppLockViewModel` is scoped to the activity, so an unlock survives rotation and ends with the process: a fresh start is always locked. The choice is stored in its own `app_lock` SharedPreferences file, not the settings store, so exporting or restoring a backup never switches it.
- The first read runs on the injected `io` context. `enabled` is null until the read finishes and the app counts as locked until then. `preferences` is set together with `enabled`; writes to it only queue, so `update` runs on the main thread, in call order, and outlives the ViewModel. `update` does nothing before the choice is read, and unlocks the session so switching the lock on leaves the app open.
- `appLockViewModel()` returns the one lock shared by the app root and Settings.
- `AppLockGate` composes its content only while unlocked, so nothing plays or can be read behind the lock; saved UI state is kept in a saveable state holder and restored on unlock. While `enabled` is null it shows nothing: neither the content nor a prompt its owner may have switched off. While the lock is on or not yet known, the app is left out of the recent-apps preview (`setRecentsScreenshotEnabled`).
- `AppLockScreen` asks once each time the app comes to the front (on resume after start, and not while in picture-in-picture); after a cancel the Unlock button asks again.
- `confirmDeviceOwner` accepts a weak biometric or the screen lock. Only a device with no screen lock reports `NO_SCREEN_LOCK`, and `AppLockScreen` treats that as passed so the device cannot shut its owner out. A missing activity or keyguard, or a prompt that cannot be shown right now, counts as cancelled, so the lock holds and can be tried again.
- `AppLockRow` asks for the same proof when the lock is switched on or off. With no screen lock the app lock already does nothing, so it may still be switched off but cannot be switched on. The row is inside the gate, so it shows only once the stored choice is known.

## Consequences

An owner without a screen lock gets no protection and no lockout. A cancelled prompt leaves the app locked. Changing the grace period or moving the choice into the settings store (and so into backups) changes what a restore can do to the lock.

## Applies to

- `app/src/main/java/com/theoriacodex/app/applock/AppLock.kt`: `APP_LOCK_GRACE_MS`, `AppLockSession`, `AppLockViewModel`, `appLockViewModel`, `AppLockGate`, `AppLockScreen`, `confirmDeviceOwner`
- `app/src/main/java/com/theoriacodex/app/settings/AppLockRow.kt`: `AppLockRow`
