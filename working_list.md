# Working List

## Pending

- None.

## In Progress

- None.

## Done

- [x] Initialize the task checklist
  - Replaced the completed OCR rollout checklist before editing.
- [x] Define the settings data flow, UI states, and acceptance checks
  - Keep the existing opt-in and device OCR model controls; present server translation as always-to-English with no downloadable translation packs, and state the phrase-only privacy boundary.
- [x] Rebuild the Viewer translation settings around the server-backed flow
  - Renamed and regrouped the UI around automatic translation, on-device recognition, server-provided English output, and phrase-only data sharing.
- [x] Update focused presentation coverage
  - Updated summary and recognition-state assertions for the new user-facing terminology.
- [x] Run the bounded validation batch
  - Debug Kotlin compilation and the focused settings presentation suite pass. Detekt reports no finding in the changed settings file; its task remains red on 70 unrelated existing complexity/length findings elsewhere.
