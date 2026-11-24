# 0144. Codex library commands run serially in a retained owner, with Undo offered in the app's undo slot

Status: Accepted

## Context

Library commands (create, rename, reorder, clean, remove posts, delete) change durable data while the screen that issued them can disappear, rotate or be cancelled. Their Undo and Retry have to survive that, and a Codex deletion must not be final while the user can still undo it. Likes is a system Codex whose removal would unlike posts, so it is treated differently from other Codices.

## Decision

- `CodexCommandViewModel` runs mutations one at a time under a mutex and keeps their feedback until it is acknowledged, so cancelling a UI collector loses no Retry or Undo. Notices without an action show as snackbars; Undo and Retry move into the app's `UndoSlot` through `offer`, anchored where each command was given. `CodexCommandHost` shows only the newest notice, and its `onDeleted` callback must be idempotent because it hears the pending-delete ids whenever they change.
- A successful rename or restored reorder gives no notice (`CodexCommandFeedback.quiet`): the Library shows the result itself.
- Deleting a Codex is deferred destruction: it hides at once and is deleted when the Library is left (`commitDeletes`) or the owner is cleared, in a `removalScope` that must be application-lived. The commit also stops and drops the Codex's offline copy (`releaseOffline`). Deleting Likes clears it instead, through the slot.
- Only clearing Likes asks for confirmation, because it also unlikes every post; deleting any other Codex acts at once and offers Undo.
- `removeWithOwnUndo` removes a post under the same mutex for a surface with its own Undo (the triage deck): no feedback is offered, it returns what restores exactly that membership (or null when the post was not in the Codex), and it throws when the write fails.
- Cleaning compares the opened Codex against other real Codices only (`codexCleaningComparisons` excludes virtual ones). Review is offered only for a real Codex, never Likes (removal would unlike), and only when the app provides a triage deck. `CodexCleaningService` keeps membership comparison and snapshot decoding off the UI thread.
- The auto-save rules sheet reads the Codex from the live list, because rule edits arrive there and the tapped snapshot would go stale. Likes has no auto-save row because it already receives every liked post. The rules' tag picker is a bounded lazy list, since a large Codex can offer thousands of tags inside a scrolling sheet.

## Consequences

Commands never overlap, and a screen can be torn down mid-command without losing its outcome. Deletion stays reversible until the user leaves the Library, so anything that must see the deletion earlier has to read `pendingDeletes`. A different scope than an application-lived one for `removalScope` would let a deletion be skipped.

## Applies to

- `feature-library/src/main/kotlin/com/theoriacodex/app/codex/CodexCommandViewModel.kt`: `CodexCommandViewModel`, `CodexCommandFeedback`, `delete`, `commitDeletes`, `removeWithOwnUndo`
- `feature-library/src/main/kotlin/com/theoriacodex/app/codex/CodexCommandHost.kt`: `CodexCommandHost`
- `feature-library/src/main/kotlin/com/theoriacodex/app/codex/CodexListScreen.kt`: `CodexDeleteConfirmation`, `CodexActionSheet`
- `feature-library/src/main/kotlin/com/theoriacodex/app/codex/CodexCleaningService.kt`: `CodexCleaningService`
- `feature-library/src/main/kotlin/com/theoriacodex/app/codex/CodexCleaningSheet.kt`: `codexCleaningComparisons`, `rememberCleaningReview`
- `feature-library/src/main/kotlin/com/theoriacodex/app/codex/CodexAutomaticTags.kt`: `CodexAutoSaveSheet`, `AutoSaveTagList`
