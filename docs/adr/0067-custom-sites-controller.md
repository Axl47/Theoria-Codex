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

## Consequences
Removing and re-adding a site restores its library. Cookie sharing is limited to setup, saved and verified hosts.

## Applies to
- `app-logic/src/main/kotlin/com/theoriacodex/app/customsites/CustomSitesController.kt`: `CustomSitesController`, `testBuiltIn`, `checkVerification`, `completeVerification`, `retest`, `setSetupHost`, `remove`, `requestSetup`
