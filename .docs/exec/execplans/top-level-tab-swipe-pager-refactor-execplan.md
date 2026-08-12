---
created_at: 2026-03-05T01:16
updated_at: 2026-08-12T04:30
---
# Top-Level Tab Swipe Pager Refactor

This ExecPlan is a living document. Keep `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` updated as implementation proceeds.

## Purpose / Big Picture

The current top-level swipe behavior is implemented by applying horizontal offset and manual pointer handling directly on `NavHost`. This approach is causing gesture conflicts, transition stalls, and crashes. The goal is to move top-level tab navigation to a pager-based architecture that natively supports finger-tracked horizontal drag and adjacent-page rendering.

## Root Cause Summary

1. **Architecture mismatch**
   - `NavHost` composes one active destination at a time.
   - Finger-tracked paging needs current and adjacent pages composed simultaneously.
   - Manual offset + delayed route swap cannot reliably emulate real interactive paging.

2. **State/gesture races**
   - Pointer input and navigation changes happen in separate coroutines.
   - Mid-gesture state updates (`offset`, `pending commit`, route change) can desync transitions.

3. **Axis arbitration complexity**
   - Vertical scroll surfaces (especially sparse Search) compete with horizontal tab gesture interception.
   - Manual touch slop/bias tuning has repeatedly regressed behavior.

## Progress

- [x] (2026-03-05 17:30Z) Reproduced symptom profile from user feedback and validated that existing transition model is unstable.
- [x] (2026-03-05 17:35Z) Completed root-cause analysis (NavHost-level emulation vs pager semantics).
- [x] (2026-03-05 18:02Z) Implemented pager-based top-level host under `AppRoute.Home`.
- [x] (2026-03-05 18:05Z) Rewired bottom bar selection/taps, tab persistence, and tab-jump callbacks to pager state.
- [x] (2026-03-05 18:07Z) Removed NavHost swipe emulation pointer/offset logic and obsolete constants.
- [x] (2026-08-12) Local validation was already complete; the user has now accepted vertical scroll arbitration and repeated fast horizontal swipes on device.

## Decision Log

- Decision: Replace manual `NavHost` swipe emulation with `HorizontalPager` for top-level tabs.
  - Rationale: Pager already models bidirectional edge-aware drag, adjacent-page composition, and animation lifecycles that match desired UX.
  - Date/Author: 2026-03-05 / OpenCode

- Decision: Keep `NavHost` only for non-tab routes (`Viewer`, `CodexDetail`) and place tabs under a single `Home` destination.
  - Rationale: Limits navigation complexity while preserving existing deep routes.
  - Date/Author: 2026-03-05 / OpenCode

## Planned Architecture

1. Root `NavHost` destinations:
   - `home` (contains top-level pager)
   - `codex detail`
   - `viewer`

2. Inside `home`:
   - `HorizontalPager(pageCount = TopLevelDestination.entries.size)`
   - Pages map 1:1 with Search / For You / Explore / Codex / Settings composables.
   - Bottom bar selected state mirrors pager `currentPage`.
   - Bottom bar taps call `animateScrollToPage(targetIndex)`.

3. Persistence and restores:
   - Use saved last-tab route to compute initial pager index.
   - Persist route when pager `currentPage` changes.
   - `onGoToSearch` style callbacks scroll pager instead of `navController.navigate(topLevelRoute)`.

## Implementation Steps

1. Introduce `AppRoute.Home` and make it root start destination.
2. Extract current tab composables into a `TopLevelPagerContent` block.
3. Replace manual `.pointerInput` and `.offset` swipe logic with pager state.
4. Update all tab-targeting callbacks (`onGoToSearch`, Explore apply+navigate, bottom nav taps).
5. Keep viewer/codex detail navigation unchanged through `navController` routes.
6. Remove obsolete swipe constants and state fields.

## Validation Checklist

- Vertical scrolling in Search works normally with no accidental tab gestures.
- Horizontal tab swipe tracks finger and reveals adjacent page during drag.
- Release behavior settles naturally to current/next page with no stuck offsets.
- No crash while repeatedly swiping between tabs and quickly interacting.
- Bottom nav taps and programmatic tab jumps still work.
- Viewer and Codex detail routes continue to function.

## Risks and Mitigations

- Risk: Regressions in route-based code expecting top-level routes in `NavBackStackEntry`.
  - Mitigation: Keep tab route as explicit app state (`selectedTab`) and update dependent logic accordingly.

- Risk: State loss across pages due recomposition.
  - Mitigation: Use `rememberSaveable` and existing coordinator state holders; avoid rebuilding coordinators per page.

## Outcomes & Retrospective

Implementation completed as planned: top-level tabs now live inside `HorizontalPager` under a single `home` Nav destination while `viewer` and `codex detail` remain dedicated routes. This eliminates the previous architecture mismatch where manual pointer-offset behavior attempted to emulate paging on top of `NavHost`.

Validation status:

- Passed: `./gradlew :app:compileDebugKotlin`
- Passed: `./gradlew :app:testDebugUnitTest`
- Passed: `./gradlew :app:assembleDebug`
- Passed: user-reported device verification for vertical scroll arbitration + repeated fast horizontal swipes on 2026-08-12.
