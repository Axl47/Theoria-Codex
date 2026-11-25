# 0206. The picture tagger is accepted on a device in phases that switch charging and idleness between them

Status: Accepted

## Context

The tagger's jobs only run while the device charges, and frames only while it is idle, so a single run cannot show that indexing waits when it should, that frames improve on stills, or that deleting the model clears predictions.

## Decision

- `VisionAcceptanceActivity` is installed only in the acceptance APK and drives the tagger through the app's own container, one phase per launch, logging `PASS:<phase>:<numbers>` or `FAIL:<phase>:<reason>`. `scripts/verify_vision_acceptance_device.sh` runs the phases in order, switching charging between them. Posts are fixtures: synthetic line drawings, with model files served from the host's loopback server at `BuildConfig.VISION_MODEL_BASE_URL`.
- On battery the indexing job is scheduled but nothing is tagged after `IDLE_WAIT_MS`. Charging with the app on screen indexes every post from its still; videos are indexed from their colour posters, so no video carries the monochrome that only its frames show, and progress counts the videos as tagged but not finished until their frames are read. The idle job then replaces each video's still analysis with one that read its line-drawing frames.
- Fixtures: pictures 0 until `FIXTURES` (24) fill the Library, with colour from `COLOUR_FROM` onwards, so monochrome is on three quarters of it, common but not universal. One more line drawing, not in the Library and with no provider tags, is the untagged candidate.
- Frame sampling is run as the job does it, timed, with frames from different points told apart (each second of a fixture video shows another drawing); at least `MIN_DISTINCT_FRAMES` (4) of the six points must differ. The host counts the bytes each fetched.
- The Library's predicted tags reach the For You profile, and lead a Danbooru lane with no provider tags at all. An untagged post is caught by a hidden tag only through what the tagger sees in its picture; the same picture on a post its provider tagged richly is left to its own tags.
- Deleting the model empties the index, which the tagger sees a moment after the delete returns.

## Consequences

The acceptance run needs a device whose charging state the script can change. Changing the fixture ratio or the frame count changes the numbers the phases assert.

## Applies to

- `app/src/releaseAcceptance/java/com/theoriacodex/app/acceptance/VisionAcceptanceActivity.kt`: `VisionAcceptanceActivity`, `FIXTURES`, `MIN_DISTINCT_FRAMES`, `idle`, `stills`, `sample`, `forYou`, `hidden`
