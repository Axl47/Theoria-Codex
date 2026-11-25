# 0218. A saved search is edited in Search and updated in place, starting its new posts over

Status: Accepted

## Context

A saved search could only be renamed. Search already restores a saved query and has every control for tags, groups, sources, filters and sort, so a second editor would duplicate it. A saved search's identity (id, name, place in the list, save date) is what the Searches collection, its exclusions and its new-post marks hang on, while its marks describe the results of one particular query.

## Decision

- The rename dialog's Edit search (`SavedSearchNameDialog` `onEdit`) keeps a name typed in the dialog first, then opens the saved search in Search the way tapping it does (`TheoriaShell.editSavedSearch`) and marks Search as editing it (`editingSavedSearchId`, saved with the composer's open state so a configuration change or process death keeps both). Opening any other search from outside Search ends the edit.
- `SavedSearchEditingEffect` opens the composer when an edit starts and ends the edit when the composer closes, however it closes. Nothing is saved on the way out, so backing out leaves the saved search as it was.
- While editing, the composer's saved-search action is Update “name” in place of Save (`composerSavedSearches`, `ComposerSavedSearchUpdate`). It saves the applied search, the one whose results are on screen, like Save does, so it is enabled only once the edit has run. First the saved search itself must have loaded in this edit (`rememberSavedSearchEditLoaded`, not saved across recreation): if Search never applies it (its source is unavailable, or process death restored another query, such as a Multi-Search Search does not persist), Update stays off rather than overwrite the pin with an unrelated query. Then it stays off while the draft differs from the applied search or a live run is waiting (ADR 0139), and while the applied search still replays as the saved one. Unlike Save it needs no included tag, since a pin from Recents may have none. When another saved search already holds the applied search, Update stays off and the composer names that search. Tapping Update closes the composer, which ends the edit, so the confirmation or a storage error shows in the app's snackbar instead of under the sheet.
- `SavedSearchRepository.update` replaces only the search and keeps id, name, save date and place, under the same lock and snapshot write as `rename`. Searches are compared by what they replay (`replaysAs`): the mode-normalized query and kind, plus the source list for a Multi-Search; not the stored hash, which can differ for the same search, nor a Unified search's sources, which Recents records but replay ignores. An update to a search that replays as the one it holds writes nothing and returns false. An update that replays as another saved search is refused with that search's name, so edits never create a second pin for one search and nothing is merged or removed; the composer checks the same rule first so the refusal is only a backstop. `save` keeps its stricter `savedFor` match. Backup `merge` and `replaceAll` need nothing new: they replace by id, so an edited search restores as edited.
- A changed search starts its new posts over (`resetSavedSearchNewPosts` drops its marks and its check attempt), so the next check sets a fresh baseline for the new query instead of counting its results as new against the old one's. A check of the old query is dropped when it lands if the stored saved search no longer holds the search it ran, releasing its own claim so the edited search stays due, or if its claim (`attempts`) no longer stands (`SavedSearchNewPostsChecker.checkIfStale`). The first covers a check that claims after the edit from a list read before it; the second covers an edit landing between that read and the write.
- The Searches collection reloads a saved search whose search changed (`SavedSearchesCodexViewModel`, ADR 0152), so it runs the edited query on its next load.

## Consequences

Editing reuses Search entirely, at the cost of an edit mode that lives only as long as the composer stays open: closing the sheet to look at results, including by submitting the input, ends the edit unsaved. Results stay visible above the sheet (ADR 0137), which is where the edit is meant to be checked. An edited search shows no new posts until the next check after the edit.

## Applies to

- `feed-kit/src/main/kotlin/com/theoriacodex/app/recents/SavedSearchNameDialog.kt`: `SavedSearchNameDialog`
- `feature-library/src/main/kotlin/com/theoriacodex/app/codex/SavedSearchesCodexFilters.kt`: `SavedSearchesCodexFilters`
- `app/src/main/java/com/theoriacodex/app/ui/TheoriaShell.kt`: `editSavedSearch`, `endSavedSearchEdit`, `editingSavedSearchId`
- `app/src/main/java/com/theoriacodex/app/ui/routes/SearchRoute.kt`: `SavedSearchEditingEffect`
- `feature-search/src/main/kotlin/com/theoriacodex/app/search/SearchScreen.kt`: `composerSavedSearches`, `rememberSavedSearchEditLoaded`
- `feature-search/src/main/kotlin/com/theoriacodex/app/search/SearchComposerModel.kt`: `ComposerSavedSearchUpdate`
- `feature-search/src/main/kotlin/com/theoriacodex/app/search/SearchComposer.kt`: `ComposerSavedSection`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/SavedSearchRepository.kt`: `SavedSearchRepository.update`, `replaysAs`, `savedFor`
- `feature-library/src/main/kotlin/com/theoriacodex/app/recents/SavedSearchViewModel.kt`: `SavedSearchViewModel.update`
- `feature-library/src/main/kotlin/com/theoriacodex/app/codex/SavedSearchNewPosts.kt`: `resetSavedSearchNewPosts`, `SavedSearchNewPostsChecker.checkIfStale`

## Guarded by

- `core-data/src/test/kotlin/com/theoriacodex/data/repository/FileBackedSavedSearchRepositoryTest.kt`: `update replaces only the search, keeps id name date and place, and survives restart (ADR 0218)`, `update to the same search changes nothing and to another pin's search is refused (ADR 0218)`, `searches replay alike whatever their stored hash, and Unified ones whatever sources Recents recorded (ADR 0218)`
- `feature-library/src/test/kotlin/com/theoriacodex/app/recents/SavedSearchViewModelTest.kt`: `updating a saved search's query starts its new posts over, and an unchanged update keeps them (ADR 0218)`, `an update matching another saved search is refused and changes nothing (ADR 0218)`
- `feature-library/src/test/kotlin/com/theoriacodex/app/codex/SavedSearchNewPostsTest.kt`: `editing a saved search starts its new posts over and drops a check of the old query still running (ADR 0218)`, `a check started before an edit drops what the old query found and leaves the edited search due (ADR 0218)`
- `feature-library/src/test/kotlin/com/theoriacodex/app/codex/SavedSearchesCodexFiltersTest.kt`: `the rename dialog's Edit search hands over that saved search and keeps a name typed first (ADR 0218)`
- `feature-search/src/test/kotlin/com/theoriacodex/app/search/SearchComposerUiTest.kt`: `editing a saved search swaps Save for Update, which hands over the search on screen (ADR 0218)`, `Update waits for the saved search to load and a change to run, and names the saved search it would duplicate (ADR 0218)`, `a duplicate shows why Update is off (ADR 0218)`, `without a saved search being edited the composer offers Save, not Update (ADR 0218)`
- `app/src/test/java/com/theoriacodex/app/ui/routes/SavedSearchEditingEffectTest.kt`: `editing a saved search opens the composer and closing it ends the edit without saving (ADR 0218)`
- Left to prose: that opening another search from outside ends the edit lives in `TheoriaShell.openHistoricalSearch`, which has no host seam without the app shell; `SavedSearchEditJourneyTest` covers the full path on a device.
