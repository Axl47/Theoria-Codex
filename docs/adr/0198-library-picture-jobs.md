# 0198. Library picture work runs as two JobScheduler jobs that wait for charging, unmetered network and, for frames, an idle device

Status: Accepted

## Context

Hashing and tagging the Library's pictures can fetch previews the cache lost and sample video frames, and a run keeps the CPU and decoders busy. It must not compete with a user who is using the phone, and it must not be lost when the process dies or the device restarts.

## Decision

- `LibraryJob.schedule` builds both jobs with `setRequiresCharging(true)`, `setRequiresBatteryNotLow(true)`, `NETWORK_TYPE_UNMETERED` and `setPersisted(true)`. Only `FRAMES` also sets `setRequiresDeviceIdle(true)`: nobody is using the device, so no card plays and no Like tap waits on the decoders. `PICTURES` hashes and tags from stills; `FRAMES` upgrades playing posts' still analyses with their frames.
- Scheduling is cheap to repeat. `schedule` marks work requested, leaves a pending job alone, and does nothing while a run is under way (the running job is asked to run again instead), so callers schedule whenever the Library or the model changes. `keepLibraryPicturesScheduled` does so at every start, when the model becomes absent or ready, and when the Library gains or loses a post (`observeLibraryTotal`).
- `LibraryJobService.onStartJob` clears the requested flag when a run starts. When the work asks to run again, or work arrived during the run (a post saved while charging), the job reports `jobFinished` with reschedule set. `onStopJob` cancels the run; the next time the conditions hold, work picks up where it stopped. A cancelled run is not reported finished a second time. Other failures are reported to `Diagnostics` and end the run without asking for another unless work arrived meanwhile.
- The tagger model lives in no-backup storage (`noBackupFilesDir/models`): it is large and downloads again. Its files come from `BuildConfig.VISION_MODEL_BASE_URL`. Deleting the model removes every stored prediction (`tagger.forget()`). Hashing runs whether or not the model is there. The thermal gate is the backfill's `hot` check at `THERMAL_STATUS_MODERATE`.
- `AppPictureSource` decodes stills at 448 px, the model's square input, because decoding larger only costs memory. Stills go through the image loader and reuse the disk cache that cards fill; video frames are sampled through the shared playback cache (`videoPlaybackInfrastructure().sampleFrames`) or from a downloaded copy. A post's motion comes from its downloaded copy when there is one, else from the listing (a video stream, or a Pixiv animation archive). A post whose listing gave no stream yet, or, for a video, whose server refuses its saved address (addresses expire), is looked up again once through `resolvePost`; a refused animation archive is not looked up again. A post refused again after the lookup has nothing beyond the still read. One post's downloads, retries and a refreshed address included, share one `NetworkBudget` of `MAX_POST_NETWORK_BYTES` (6 MiB): a light rendition's index and six keyframes, or a 600 px animation archive, usually take well under that.

## Consequences

Tagging progresses only while the phone is plugged in, on Wi-Fi-class networks, and frame upgrades only while it sits idle. Loosening a constraint trades battery, heat and data use for speed.

## Applies to

- `app/src/main/java/com/theoriacodex/app/vision/LibraryPictureJobService.kt`: `LibraryJob`, `schedule`, `LibraryJobService`, `keepLibraryPicturesScheduled`
- `app/src/main/java/com/theoriacodex/app/vision/AppPictures.kt`: `appPictures`, `AppPictureSource`, `INPUT_PX`, `MAX_POST_NETWORK_BYTES`
