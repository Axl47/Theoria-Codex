# 0067. Custom site verification and setup never touch library data and bind cookies to the in-app browser

Status: Accepted

## Context
Sites behind browser challenges only answer requests that carry the in-app browser's clearance cookies and user agent. Users also add, retest and remove sites while the app holds library data for them.

## Decision
- `checkVerification` tests requests with the browser's user agent and changes nothing: a built-in site's home page and any of its hosts in an outage must answer without a challenge; a stored site is probed with its keys and headers. `completeVerification` then makes the site send those cookies and user agent, lifts its outage, flushes cookies and makes no request.
- `testBuiltIn` runs a real search, retrying even during an outage, and discards the posts.
- `retest` refreshes the verified time on a same-family success; a different family is reported, never switched to.
- `setSetupHost` lets cookies reach a host only while the Add form sets it up; saved and verified built-in sites always get them.
- `remove` deletes the profile, credentials and Unified/For You opt-in but never library data.
- A link requested with `requestSetup` opens once the site is saved; abandoning the form discards it.
- `CustomSitesControllerImpl.add`: the same address always derives the same source id, so stored library rows may belong to a removed site. Without `confirmReconnect`, a source with library data returns `ReconnectConfirmationNeeded`. A saved site clears its host's outage record, since the check that led to the add just got an answer and a mark from before a removal no longer applies. A `linkAfterSetup` is published only after the live source (rebuilt just after the keys are written, with matching `hasCredentials`) appears, waiting at most `LIVE_SOURCE_WAIT_MS` (5 s).
- `retest` re-probes with the stored family known; a different family is reported to the user, never adopted (remove and re-add to switch). A working result also refreshes the icon the site declared since, or one missed before.
- `testBuiltIn` consults the outage tracker before the search result: a source with a fallback mirror can return posts while its own host is challenged, so a challenge maps to `NEEDS_BROWSER_CHECK` regardless. The search is bounded by `BUILT_IN_TEST_TIMEOUT_MS` (20 s): long enough for a slow first search, short enough that one stuck source does not stall Test all.
- `checkVerification` for a built-in checks its home page and every host of it currently in an outage, because an API host is often what challenged and it can stay challenged after the home page clears. For a stored site it keeps the probe result, and `completeVerification` marks the site working only when that check also found the expected booru family, not merely got past the challenge.
- `update`: new or cleared keys clear the host's outage so requests reach the site again. Each header edit is applied to the profile as stored at that moment (`profiles.update`), so a header saved meanwhile, such as a browser check's agent, stays.
- `remove` clears the keys first, so a profile is never left removed while its keys survive; a failed key write returns `CredentialStoreUnavailable` and leaves the profile.

## Consequences
Removing and re-adding a site restores its library. Cookie sharing is limited to setup, saved and verified hosts.

## Applies to
- `app-logic/src/main/kotlin/com/theoriacodex/app/customsites/CustomSitesController.kt`: `CustomSitesController`, `testBuiltIn`, `checkVerification`, `completeVerification`, `retest`, `setSetupHost`, `remove`, `requestSetup`
- `app/src/main/java/com/theoriacodex/app/di/CustomSitesControllerImpl.kt`: `add`, `retest`, `testBuiltIn`, `checkVerification`, `completeVerification`, `update`, `remove`, `LIVE_SOURCE_WAIT_MS`, `BUILT_IN_TEST_TIMEOUT_MS`
