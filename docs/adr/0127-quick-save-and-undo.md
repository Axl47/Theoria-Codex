# 0127. One-tap saves create Codices only when they run and Undo reverses exactly what the save added

Status: Accepted

## Context

Saves start from several surfaces (Viewer, feed grids, lift drops, the triage deck) and all of them offer Undo. A later save elsewhere, a cancelled caller, or an Activity recreation must not make Undo remove the wrong thing or leave a half-made Codex.

## Decision

- A Codex chosen in the picker or the Create Codex dialog is only a choice (`ChosenCodex`); it is created when `QuickSave.save` runs, so Undo can remove it again. The dialog runs its callback in the shell's scope so the save finishes even if the asking screen has gone.
- `quickSave` finishes even if its caller is cancelled.
- `QuickSaveRecord` carries what the save added: `inserted`, the membership's `membershipSavedAtEpochMs` (tells it apart from a later save of the same post), and `createdCodex`. Undo is offered only when `undoable` (exactly one post added), removes only that membership while it is still there, removes the Codex too when the record created it and it is empty, and clears `lastQuickSave` only while it is still that record.
- Surfaces confirm in place from the record their own save returned; nothing here shows a snackbar.
- `SAVED_CODEX_ID` is a presentation container only; its children keep their own storage identities.
- `PostMutationViewModel` keeps captured destinations and commit callbacks across Activity recreation without a second membership state, and retains the post through shelf setup because membership can emit before `toggle` returns.

## Consequences

A save can always be undone precisely, at the cost of carrying a record per save. Changing Undo to a plain "remove last" would break after a later save elsewhere.

## Applies to

- `feed-kit/src/main/kotlin/com/theoriacodex/app/codex/ChosenCodex.kt`: `ChosenCodex`, `NewCodexOpener`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/codex/QuickSave.kt`: `QuickSave`, `QuickSaveRecord`, `undoQuickSave`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/codex/SavedCodexId.kt`: `SAVED_CODEX_ID`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/lift/LiftOverlay.kt`: `saveFailure`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/post/PostMutationViewModel.kt`: `PostMutationViewModel`
