# 0107. Frame sampling for the picture tagger has a per-post byte budget and classifies each failure by whether a retry could change it

Status: Accepted

## Context

The tagger reads a few frames per video or animation. Sampling a whole Library must not download whole files, evict what the user watched, or retry what cannot succeed.

## Decision

- `NetworkBudget` caps the bytes one post's sampling may download, counted as response bodies arrive (error bodies and skipped prefixes included) and shared by every request for that post, a refreshed address's too. Past it reads fail with `NetworkBudgetSpent`, which is final: trying again spends the same bytes. Pixiv sampling downloads the smallest archive (600 px) to a temporary file it deletes afterwards, or reads an archive already held without marking it used, so sampling a Library never evicts watched archives. Retries share the budget so they cannot multiply what one post downloads. A held archive that fails to read is forgotten as playback does; offline copies are never touched.
- `frameSample` is the one place that decides whether a failed read is worth retrying. A spent budget, media whose bytes give no frames (`UnreadableMedia`) or a source that cannot read its own answer (parse failure or unknown) keeps what was read, since another try repeats it. A refusal (`MediaRefused`, or a 4xx other than 408 and 429) asks for a fresh address, since it may have expired. Anything else (network, "try later", a sign-in or browser check) may pass on a later run, so even frames already read wait for it. Ugoira and sources speak through the same classes.
- `sampleVideoFrames` takes up to `count` sync frames spread from 5% to 95% of the length (only the first when the length is unknown) using `MediaMetadataRetriever`, which decodes only the keyframe at each point and reads only the index and those keyframes. It is blocking and must run off the main thread; the job ending stops the reads.
- `DataSourceMediaReader` serves the retriever's random reads from a Media3 source in windows of at most 256 KiB (`WINDOW_BYTES`), each by one request, keeping the last four (`MAX_WINDOWS`) since the retriever rereads the index for every frame. The first request asks for the whole file to learn its length but reads only its first window. The first failure is kept: after it, a read that misses the kept windows fails, while a window already kept is still served. The retriever calls from its own threads one at a time, so each call holds the reader's lock. Windows are searched through entries rather than `get()`, which reorders the access-ordered map mid-iteration. Once the job ends the reader gives nothing more, kept windows included, and a fetch that outlasts the job is kept but not handed over.
- `sampleUgoiraFrames` picks the frame showing at the middle of each equal stretch of playing time (a frame without delay counts 1 ms), so a frame held across several points is returned once per point and keeps its share of the animation. A missing or undecodable frame is skipped since the archive will not change on a later try.

## Consequences

A Library can be tagged without data-plan or cache cost beyond the budget, and transient failures are retried while permanent ones are not.

## Applies to

- `media-kit/src/main/kotlin/com/theoriacodex/app/viewer/VideoFrameSampling.kt`: `NetworkBudget`, `NetworkBudgetSpent`, `frameSample`, `sampleVideoFrames`, `refusesMedia`, `DataSourceMediaReader`, `samplePointsUs`
- `media-kit/src/main/kotlin/com/theoriacodex/app/viewer/PixivUgoiraFrames.kt`: `sampleUgoiraFrames`, `ugoiraSampleIndices`
- `media-kit/src/main/kotlin/com/theoriacodex/app/viewer/PixivUgoiraClient.kt`: `sampleFrames`, `heldFrames`, `downloadedFrames`

## Guarded by

- `media-kit/src/test/kotlin/com/theoriacodex/app/viewer/VideoFrameSamplingTest.kt`: `one budget counts every response body it sees, then refuses (ADR 0107)`, `frames wait for a later try after a network failure or a sign-in, and a refusal asks for a fresh address (ADR 0107)`, `sample points spread from 5 to 95 percent, or the first frame when the length is unknown (ADR 0107)`, `the reader keeps the windows it fetched, so reading them again costs no request (ADR 0107)`, `the reader keeps its last four windows and refetches an evicted one (ADR 0107)`, `after the reader's first failure a read outside its kept windows fails while a kept window is still served (ADR 0107)`
- `media-kit/src/test/kotlin/com/theoriacodex/app/viewer/PixivUgoiraClientTest.kt`: `sampling frames leaves the playback store alone, a refused post samples nothing, and a refused sign-in waits (ADR 0107)`, `sampling downloads the 600 px archive into a file it deletes and reads a held archive without marking it used (ADR 0107)`, `every try at one post's archive spends the same sampling budget (ADR 0107)`, `a held archive whose frames fail to read while sampling is forgotten (ADR 0107)`
- `media-kit/src/test/kotlin/com/theoriacodex/app/viewer/PixivUgoiraFramesTest.kt`: `the tagger's sample spreads frames through the animation and decodes them no smaller than asked (ADR 0107)`, `a frame held longer is sampled once per stretch of time it fills (ADR 0107)`, `a frame without delay still counts one millisecond of playing time (ADR 0107)`, `a frame held longer is returned once for each stretch it fills (ADR 0107)`
- Left to prose: that the video sampler hands one budget to every request for a post, a refreshed address's too, is not yet guarded, testable in media-kit (Pixiv sampling's shared budget is guarded); what `MediaMetadataRetriever` decodes and reads is decoder behaviour only a device shows; that `sampleVideoFrames` runs off the main thread rests on its one caller in `VideoPlaybackInfrastructure`; that offline copies are never touched, that a fetch outlasting the job is not handed over, and that a missing or undecodable frame is skipped while sampling are not yet guarded, testable in media-kit; the reader's lock is a threading rule for the retriever's callers.
