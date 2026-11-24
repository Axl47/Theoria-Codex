# 0160. The Extensions page asks before turning an extension on, hides text-setting values, and lists only what it has just compared

Status: Accepted

## Context

An extension's code runs inside the app and can read its data, so turning one on is the one costly mistake on this page. Extension files can change under the page (an install, an update, a file put in the debug folder), and repo reads are slow and can be superseded by newer requests.

## Decision

- An extension runs only once it is switched on in the list or installed from a repo through the app. Its switch is a labelled box, not a bare `Switch`, named with the extension's name.
- An extension with no signing certificate can never be switched on; one that is on can always be switched off. `TurnOnDialog` is the only confirmation and states that the code runs inside the app and can read its data, and who signed it.
- `setOn` acts on one extension, as the signer the user was shown. When turning one on leaves it off and it now has a signer different from the one shown (its file was replaced while the question was open), the question is asked again about what is there now. Turning off, or an extension left off without a signer, asks nothing.
- A text setting can hold a password or a token, so `ExtensionSettingRows` shows its value only inside its dialog.
- The Add repo dialog cannot be dismissed while the repo is read (`AddRepo.busy`), so the answer or refusal lands in that dialog.
- The list is read again whenever the host's `revision` moves (an install, removal or switch), and the repos' offerings are compared with the device again each time. Opening the page refreshes the repos for updates. A file put in the debug folder sends no signal, so `refresh` lists again on return to the page.
- `showListing` publishes the repo list at once, then the available extensions only if no newer request began meanwhile; an earlier request still waiting on the available extensions is dropped. A request that supersedes an in-flight refresh refreshes too, through `refreshing`, so a refresh is never lost.

## Consequences

A key change still turns an extension off until the user approves again (see the series sources ADR). Any new way to turn an extension on must go through the same signer check and question.

## Applies to

- `feature-series/src/main/kotlin/com/theoriacodex/app/series/ExtensionsScreen.kt`: `TurnOnDialog`, the extension row's switch
- `feature-series/src/main/kotlin/com/theoriacodex/app/series/ExtensionsViewModel.kt`: `setOn`, `showListing`, `listingRequest`, `refreshing`, `refresh`, `AddRepo`
- `feature-series/src/main/kotlin/com/theoriacodex/app/series/ExtensionSettingRows.kt`: `ExtensionSettingRows`
- `feature-series/src/main/kotlin/com/theoriacodex/app/series/ExtensionReposScreen.kt`: `AddRepoDialog`
