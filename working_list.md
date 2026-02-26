---
created_at: 2026-02-26T00:36
updated_at: 2026-02-26T01:02
---
# Working List

## Pending
- [ ] Manual on-device smoke validation by developer checklist

## In Progress
- [~] Awaiting developer on-device smoke validation checklist results

## Done
- [x] Read current media/source/viewer implementation and identify integration points
- [x] Refresh `working_list.md` for this task-orchestrated implementation
- [x] Create and maintain `docs/execplans/video-support-cross-source-execplan.md`
- [x] Add shared source MIME inference for image/video and wire Gelbooru/AIBooru/Pixiv adapters
- [x] Implement Viewer video playback path while preserving Pixiv ugoira rendering/export flow
- [x] Unify animated/media-kind detection across Search, Viewer, and app session merge logic
- [x] Add tests (`MediaMimeTest`, `GelbooruSourceAdapterTest` video case, `PostMediaTest`) and pass validation:
  - `./gradlew :core-sources:test :app:testDebugUnitTest`
  - `./gradlew assembleDebug`
- [x] Update docs (`README.md`, `AGENTS.md`)
- [x] Patch viewer/download request headers for Gelbooru/AIBooru hotlink compatibility and revalidate (`./gradlew :app:testDebugUnitTest assembleDebug`)
- [x] Add autoplay muted video previews in Search/Codex cards with image fallback and source headers; revalidate (`./gradlew :app:testDebugUnitTest assembleDebug`)
- [x] Add shared interactive media scrubber and wire draggable seek controls for Viewer ugoira/GIF/video playback (`./gradlew :app:testDebugUnitTest assembleDebug`)
