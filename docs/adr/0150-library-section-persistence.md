# 0150. The Library's selected section is app-wide, persisted by name

Status: Accepted

## Context

Other screens can jump to a Library section before the Library shows, and the choice must survive restarts, including after tabs were folded into sections.

## Decision

- `LibrarySectionStore` is app-wide so a jump from anywhere can pick a section before Library shows; every choice is persisted in the UI-restore store and restored at startup.
- Sections are stored by enum name (`LibrarySection.name`), which is what the store keeps; `LibrarySection.named` returns null for anything unknown.
- The write launched after each choice stores the selection current when that write runs, not the one that triggered it. Writes run concurrently on IO, so their order is not guaranteed.
- `restore` applies a legacy section (from a tab that became a section) when given, otherwise the stored one.

## Consequences

Renaming a `LibrarySection` entry changes the stored data. A stored name that matches no section is treated as absent.

## Applies to

- `feature-library/src/main/kotlin/com/theoriacodex/app/library/LibraryHeader.kt`: `LibrarySection`
- `feature-library/src/main/kotlin/com/theoriacodex/app/library/LibrarySectionStore.kt`: `LibrarySectionStore`, `restore`
