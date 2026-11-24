# 0148. Settings → Sites writes edits live, defers removals for Undo, and ties browser checks to the form that opened them

Status: Accepted

## Context

The Sites screen mixes forms (Add, Edit), per-row tests, browser checks and removals of sites that hold secrets. Typing must not lose or revert changes, an Undo must never have to restore secrets from memory, and cookies must not stay shared with a site the form no longer concerns.

## Decision

- Removing a site hides it at once with Undo in its row; the site and its keys are removed only when the Undo times out or Sites is left (`CommitRemovals`), so Undo never has to write secrets back from memory. Removal commits in a `removalScope` that must outlive the owner. Removing a site never touches library data.
- `SiteLiveEdits` saves the Edit page's name, opt-in and headers shortly after each change (`TYPING_SAVE_DELAY`), so only keys need a Save. Only the fields the user changed are written, with the last values left in them; queued changes are written one at a time, in order, in a scope that outlives the page's owner, so a write started or flushed by `close` still completes. A changed field equal to the saved site is left out, but one this owner already wrote is always sent again because the saved site can lag behind the write. An invalid changed field (a blank name, a bad header) is held back while the valid ones are written and is written with the next change once valid; one still invalid when the user moves to another site or the owner is cleared is never written. A field whose write fails stays changed and is retried with the site's next change.
- New keys are not checked on save, so a successful key replacement retests the site at once and shows the answer on the row. Both key fields blank mean no keys; one blank is a mistake worth pointing out; a pasted snippet in either field fills both.
- A built-in or stored site that passes its browser check updates its row, and an open Edit form takes the agent, or a later Save would revert the saved agent. The Add form's site keeps the browser's cookies (`setSetupHost`) until the check is dismissed, the address changes, or the form closes or saves; a different address is a different site, which neither the waiting link nor the old site's cookies belong to. When an Add site made the app prove itself, the key section is offered, since such a site may limit visitors without an account.
- The check page outlives the screen but its callbacks write to the screen's state, so clearing the owner closes it. A field edit that would invalidate a finished probe resets it (`ProbeReset`), so a draft always matches what was checked.
- A site that works needs no browser check; any later outage clears Working and brings the check back (built-in page and Edit page). Edit page headers fold open only at first composition, so typing never folds the section away.
- The single include switch is shared by Unified search and For You; new sites start off. Leaving Sites completes a removal still offered for Undo.

## Consequences

The Edit page has no Save for non-secret fields, so a new editable field needs an entry in `LiveField` and a validity rule. Sites removal is only final after the Undo window or leaving the screen.

## Applies to

- `feature-settings/src/main/kotlin/com/theoriacodex/app/settings/sites/CustomSitesViewModel.kt`: `CustomSitesViewModel`, `startVerification`, `remove`, `ProbeReset`, `saveEdit`
- `feature-settings/src/main/kotlin/com/theoriacodex/app/settings/sites/SiteLiveEdits.kt`: `SiteLiveEdits`, `TYPING_SAVE_DELAY`
- `feature-settings/src/main/kotlin/com/theoriacodex/app/settings/sites/CustomSitesScreen.kt`: `CustomSitesScreen`
- `feature-settings/src/main/kotlin/com/theoriacodex/app/settings/sites/BuiltInSitePage.kt`: `BuiltInSitePage`
- `feature-settings/src/main/kotlin/com/theoriacodex/app/settings/sites/CustomSiteForms.kt`: `EditSitePage`, `IncludeSwitch`, `AdvancedHeaders`
