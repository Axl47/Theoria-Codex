# Task Backlog

This backlog records agreed product improvements that are not yet scheduled for implementation.

## Product constraints

- Persistent phone-screen space must be reserved for information and controls that remain useful during ordinary repeated use.
- Prefer transient sheets, dialogs, selection modes, and snackbars over permanent explanatory copy or additional rows.
- Optimize for existing users. Do not add onboarding-oriented labels or guidance when stable iconography and placement are sufficient.
- Preserve the current media-forward layout and avoid repeating controls on every post card unless the action is both frequent and important.
- Reuse shared UI ownership when several routes need the same behavior; avoid route-specific copies of feedback, app bars, or filter presentation.

## Backlog

### UX-001 — Make collection and post actions discoverable without cluttering every card

- [x] Add a compact overflow affordance to each Codex collection tile, integrated into its existing title/item-count area, for collection-level actions such as export, share, search, rename, and delete.
- [x] Do not add a permanent overflow button to every Search, Recents, For You, Creator, or Codex post card.
- [x] Give Codex detail a screen-level Edit/selection mode for removing one or more posts without relying exclusively on card long-press.
- [x] Keep long-press as an efficient shortcut for experienced users, while ensuring every important action also has an explicit path through the collection screen, selection mode, or Viewer.
- [x] Keep transient action surfaces compact; prefer recognizable icons with short labels where ambiguity remains, not explanatory sentences.

### UX-002 — Add selective, actionable transient feedback

- [x] Add one shell-owned snackbar host positioned transiently above the bottom navigation.
- [x] Keep toasts for passive confirmations that require no response, such as copied content or a successfully queued download.
- [x] Use short, contextual snackbars only when the user can take a useful immediate action, such as Undo, Retry, Open, or Settings.
- [x] Make Recents clearing context-specific after invocation, without adding persistent copy to the header; clearly distinguish watched, Codex-origin, search, and all-history clearing.
- [x] Make recommendation-seed blacklisting reversible through a short `Seed hidden` snackbar with Undo instead of adding a permanent verbose control label.
- [x] Reserve confirmation dialogs for irreversible or materially destructive operations; do not require confirmation for ordinary reversible actions.
- [x] Do not migrate every toast to a snackbar; use the shared in-app feedback path only where it provides recovery, navigation, or reversal.

### UX-003 — Reduce Settings first-open density

- [x] Change first-run Settings expansion defaults so the page does not initially open with every section expanded.
- [x] Preserve each existing user's persisted expansion choices; the default change must not reset current UI-restore state.
- [x] Add compact collapsed summaries where they help repeated use, such as enabled-source count, account connection state, cache usage, or active recommendation profile.
- [x] Keep credentials, per-source weights, and destructive storage controls inside their expanded sections rather than exposing more permanent page content.

### UX-004 — Standardize secondary-screen chrome and feed filtering

- [x] Use one shared secondary-screen app-bar pattern with a left back affordance, title/context in the center area, and compact actions or overflow on the right.
- [x] Apply the pattern to Codex detail, Creator Profile, Viewer, and future secondary routes without increasing their existing top-bar height.
- [x] Converge Search, For You, and Creator filtering on one shared filter-sheet structure while preserving route-specific options.
- [x] Indicate active filters within the existing filter affordance through state, tint, or a small badge/dot instead of adding another persistent filter-summary row.
- [x] Keep filter controls transient and dismissible so the media grid remains the dominant surface.

### UX-005 — Reuse the collapsed Search field for applied context

- [ ] When Search is unfocused and has an applied query, reuse the existing search-field text area for a compact one-line summary such as `Pixiv · klee · 2 filters` instead of showing the generic entry placeholder.
- [ ] Keep the collapsed Search field at its current height; do not add a persistent chip row, applied-query row, or explanatory copy.
- [ ] Restore the normal text-entry presentation when the field receives focus.
- [ ] Ellipsize or compact the summary when several terms or source names cannot fit, while retaining the most useful source, query, and filter context.
- [ ] If active filters are not already clear in the collapsed summary, optionally show one small status dot inside the existing filter FAB; do not show both a redundant summary indicator and badge count.
