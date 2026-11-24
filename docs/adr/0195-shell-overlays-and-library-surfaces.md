# 0195. Shell overlays, Library sections and undo offers keep their state and scope past the screen that opened them

Status: Accepted

## Context

Some sheets, dialogs and actions are mounted once above the navigation host and used from many screens. Their writes and Undo offers must not be lost when a sheet closes, the device rotates, or the user leaves the screen. Library sections are separate surfaces that must each keep their place. Several of these surfaces save to a Codex, and a Codex waiting for its Undo window to end must not be a target.

## Decision

- `LibraryDestination` composes only the chosen section, so tab reselect scrolls whichever one shows; the sections keep their saved state in a saveable state holder, so each returns where it was left. Offline copies removed from a Codex's menu (`CodexOfflineRemovalUndo`) are committed when the Library, or the Codex detail screen that hosts the same undo, is left.
- `CodexLibraryDestination` is called from two places on purpose: the Library's Codices section and the shell's keyed action overlay above the NavHost. Each call retains its flows under its own ViewModel store owner. The overlay floats over whatever screen opened it, so its offers use the shell's own undo pill (`UndoAnchor.App`), and that overlay is composed above the shell's theme and locals, so it applies the night theme itself and provides the triage opener and transfer locals Cleaning's Review needs, which the shell provides only below.
- Recents filters no lengths, so it passes empty duration states and does not resolve durations in the background; it only reports what its cards learn.
- `ShellHiddenTagsSheet` runs unhide and its Undo in `undo.scope`, not the sheet's scope, so closing the sheet or a rotation does not drop the Undo. The Undo puts the entry back for the profile it was removed from. A failed settings write (storage full) is reported with a snackbar, not thrown into a scope with no handler.
- A Codex waiting to be deleted is never a save target: `rememberQuickSave` filters hidden ids out of suggestions (asking for extra results to still fill the limit), and \`SaveToCodexOverlay\` filters them out of the picker's Codices.
- A backup that keeps failing protects nothing, so `AutoBackupFailureEffect` shows a message where it will be seen, not only in Settings. While the offline and downloads sheet is closed, preparation failures are shown as messages; an open sheet shows the reason itself.
- `statisticsUsageCategory` keeps the meaning of the tabs Library replaced: History counts as browsing, as Recents did, and Codices and Saved count as Codex; Settings counts as nothing. In the Viewer, a series session separates reading chapters and watching episodes from watching posts.
- `TheoriaAppPlatformEffects`: the Viewer follows the phone's rotation unless `viewerRotation` holds an orientation (set by auto-rotate or the rotate button). The hold lasts while any Viewer level is on top, so moving between trail levels never rotates.
- `openInBrowser` sends web URLs through the browser-app selector (`CATEGORY_APP_BROWSER`), so the app's own link-handling filters (Open links in Settings) cannot capture them and reopen this app.

## Consequences

A new overlay that does writes or Undo needs an application-lived or Activity-lived scope, not its own composition's. A new Library section must keep its state in the saveable holder. Changing which section counts as browsing changes statistics categories.

## Applies to

- `app/src/main/java/com/theoriacodex/app/ui/LibraryDestination.kt`: `LibraryDestination`, `CodexLibraryDestination`, `rememberRecentsDurationOwner`
- `app/src/main/java/com/theoriacodex/app/ui/ShellHiddenTags.kt`: `ShellHiddenTagsSheet`
- `app/src/main/java/com/theoriacodex/app/ui/QuickSaveProvision.kt`: `rememberQuickSave`
- `app/src/main/java/com/theoriacodex/app/ui/TheoriaApp.kt`: `TheoriaAppContent`, `rememberPostMenuActions`, `SaveToCodexOverlay`
- `app/src/main/java/com/theoriacodex/app/ui/LibraryTransferTools.kt`: `AutoBackupFailureEffect`
- `app/src/main/java/com/theoriacodex/app/ui/AppNavigation.kt`: `statisticsUsageCategory`
- `app/src/main/java/com/theoriacodex/app/ui/TheoriaAppPlatformEffects.kt`: `TheoriaAppPlatformEffects`
- `app/src/main/java/com/theoriacodex/app/ui/TheoriaAppSupport.kt`: `openInBrowser`
- `app/src/main/java/com/theoriacodex/app/ui/TheoriaNavDestinations.kt`: the Codex detail entry
