# 0171. Viewer brightness persists across Viewers in a session and volume is restored only for video

Status: Accepted

## Context

Brightness and volume swipes act on the real window and the system media stream, outside any player's lifetime.

## Decision

- `ViewerSessionLevels.App` lives as long as the app process, so every Viewer opened in the same app session starts where the last one left off.
- `ViewerLevelControls` applies this session's brightness on entering fullscreen and restores the display brightness on leaving. Media volume is left where the user put it, as the system volume always is.
- Brightness holds for every media and the page overview (picture-in-picture hands it back); the remembered volume returns for video only (`restoreVolume`).
- After setting a volume the actual level is read back: safe-volume caps and device policies are respected instead of displaying a fictitious level.

## Consequences

Brightness persists for the app session only. A non-video Viewer never changes the system volume on open.

## Applies to

- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerLevelControls.kt`: `ViewerSessionLevels`, `ViewerLevelControls`, `restoreVolume`, `drag`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerScreen.kt`: `ViewerLoadedScreen`
