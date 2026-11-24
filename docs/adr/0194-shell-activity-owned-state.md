# 0194. Cross-screen shell state lives in Activity-scoped owners, process-local unless it is a small saved handoff

Status: Accepted

## Context

Android recreates the host Activity on rotation and similar changes, and a singleTask launch can redeliver the same intent. Navigation keeps an exiting destination composed briefly. A Viewer session holds resolved posts and transient stream bindings that cannot be serialized. Work started by one screen has to survive that screen's recreation without running twice.

## Decision

- `AppShellViewModel.beginStartup` is idempotent (`startupStarted`): recomposition or recreation cannot start a second update check or download.
- `IncomingUriWorkflow` owns a single pending external handoff across Activity recreation and process restoration, in the `SavedStateHandle`. `ShellIncomingUriEffects` passes an incoming link to it and then clears the Activity's underlying singleTask intent (`onIncomingUriConsumed`), so recreation cannot enqueue the same URI a second time.
- `ViewerSessionRetentionViewModel` is Activity-scoped and process-local on purpose. A Viewer session contains resolved posts and transient stream bindings, so it must not be promoted to the application container or serialized as durable state. The destination consumes and clears the holder immediately; `ViewerViewModel` is the only owner while the Viewer route is active.
- `handoffTo` transfers only the payload claimed when the route entry started: it compares `sessionId` with the claimed id. Navigation keeps an exiting destination composed briefly, and matching the claim stops that old entry from consuming a newer payload retained for a rapid Viewer re-entry.
- `RouteOwners` slots outlive individual destination compositions: the Viewer can keep paging through a still-live back-stack owner, and only that owner's clear empties its slot. One instance is held for the shell's whole lifetime and closed only when the shell leaves composition.
- One `UndoSlot` exists per Activity, held in a ViewModel (`UndoSlotOwner`, keyed `undo-slot`) so its scope outlives a recreation, like the shell's other owners.
- A Codex detail entry's tag-sheet request (`CodexTagSheetRequest`) is held in its saved state and cleared when handled, so recreation does not reopen the sheet. `openCodexTagSheet` sets it on the current entry when that entry already shows the Codex, otherwise right after navigating, which pushes the new entry at once so the request lands on the screen it opened.

## Consequences

After process death the Viewer's retained session and a triage hand-off are gone and their screens fall back (see the triage and Viewer launch decisions). Putting any of these into the application container would keep a Viewer's posts alive for the whole process.

## Applies to

- `app/src/main/java/com/theoriacodex/app/appshell/AppShellViewModel.kt`: `beginStartup`
- `app/src/main/java/com/theoriacodex/app/appshell/IncomingUriWorkflow.kt`: `IncomingUriWorkflow`
- `app/src/main/java/com/theoriacodex/app/appshell/ViewerSessionRetentionViewModel.kt`: `ViewerSessionRetentionViewModel`, `handoffTo`
- `app/src/main/java/com/theoriacodex/app/ui/TheoriaShell.kt`: `RouteOwners`
- `app/src/main/java/com/theoriacodex/app/ui/TheoriaApp.kt`: `UndoSlotOwner`
- `app/src/main/java/com/theoriacodex/app/ui/TheoriaShellEffects.kt`: `ShellIncomingUriEffects`
- `app/src/main/java/com/theoriacodex/app/ui/CodexTagSheetRequest.kt`: `CodexTagSheetRequest`, `openCodexTagSheet`
