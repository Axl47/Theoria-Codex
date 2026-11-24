# 0138. The composer adds tags to the target it shows, keeps groups to one tag type and offers Undo only while it is safe

Status: Accepted

## Context

The composer edits a query made of required groups (a group with several tags is a set of alternatives) and excluded tags, for sources with different tag vocabularies. Suggestions, saved tags and typed text all end as draft edits that the draft reducer must accept, and a removal's Undo restores a snapshot that later edits could overwrite.

## Decision

- The query shows the chosen `ComposerAddTarget.Alternative` group itself, so the only separate setting is required versus excluded. An `Alternative` keeps the group's draft index because group actions address that index.
- A group's alternatives share one tag type. For an alternative target, `composerSuggestions` offers only suggestions the draft reducer would accept (`takes`), reports how many matches that ruled out (`composerHiddenSuggestionsNote` names the accepted type), and a plain general tag joins a group in the group's own spelling. Adding to a group takes tags only, so the omnibox entries are dropped when the target is an alternative.
- Suggestion rows come in this order: saved tags, then tags the user searched before, then the provider's. Saved and searched rows are only those of the tag type the input is aimed at (`typedInputScope`); All shows every type, and a prefix the source cannot search shows none. A saved or searched tag the provider also returned keeps the provider's row so its count stays. Rows are left out when the tag is already in the draft or cannot join the target group. The typed text is normalized like the searched-tag prefix, so a leading `-` or a scope prefix such as `tag:` is ignored. At most `MAX_SAVED_SUGGESTIONS` saved tags appear so provider results stay in view, and saved groups stay in the saved row because suggestions complete one tag.
- A saved tag carries the source it was saved from, which already validated it, so it joins a group directly (`AddSavedTagAlternative`). A saved group cannot join another group, so `canTap` makes it unavailable while a group is the target.
- Commas and semicolons around typed text come from a paste or a habit and are never part of the tag (`trimTagSeparators`). The keyboard's action adds a typed tag and keeps the sheet open; on an empty field it runs the draft and closes. A typed tag is only added, so a gallery ID that opens the Viewer never applies the draft behind it.
- Only general tags can be saved (`canSave`), so a typed tag, or a group containing one, offers no save. Saving a group asks for its name in a dialog because the tag's menu closes once Save is chosen; a blank name shows the group as its alternatives.
- Saved tags follow the same add, exclude, clear cycle as tags in post info (`tapActions`); a saved group is only added, then removed. Saved searches replace the search and run it, while saved tags add to the search being built, so they are separate parts with their own Manage. "Save" on a search saves the one on screen (applied, not the draft), recorded the way Recents records it so the entry reopens the same scope. A new save goes under the draft's source, else the first source the scope covers, else the first available one (`savedTagSource`); the same tag favorited on several sources shows as one chip.
- `ComposerRemovalUndo` restores its `before` snapshot, so it stands only while the draft equals `before` (the removal has not landed yet) or `after`. Any other edit would be overwritten, and tags restored into another source could be ones it cannot search; `ComposerRemovalUndoEffect` retracts the offer then.

## Consequences

Users cannot build a mixed-type group or a group the source would reject, and Undo may disappear after an unrelated edit. Letting groups mix types, or letting Undo survive other edits, would need the reducer and the snapshot to merge rather than replace.

## Applies to

- `feature-search/src/main/kotlin/com/theoriacodex/app/search/SearchComposer.kt`: `SearchComposer`, `ComposerRemovalUndoEffect`, `ComposerSavedSection`, `SavedTagChips`
- `feature-search/src/main/kotlin/com/theoriacodex/app/search/SearchComposerModel.kt`: `ComposerAddTarget`, `composerSuggestions`, `withSearchedTags`, `withSavedTags`, `composerHiddenSuggestionsNote`, `composerTypedAction`, `trimTagSeparators`, `canTap`, `tapActions`, `ComposerSavedTags.canSave`, `ComposerRemovalUndo`
- `feature-search/src/main/kotlin/com/theoriacodex/app/search/SearchComposerQuery.kt`: `ComposerQuery`, `SaveGroupNameDialog`
- `feature-search/src/main/kotlin/com/theoriacodex/app/search/SearchScreen.kt`: `composerSavedSearches`, `savedTagSource`
