# 0096. The picture model runs on the CPU, loaded only while pictures arrive, with int8 embeddings

Status: Accepted

## Context

Phase 0 measurements on a Galaxy S24+: 389 ms cold and 449 ms sustained per picture with 4 threads (525 ms with 2). The phone's GPU gave wrong results at fp16 precision. The loaded model takes about 350 MB. The phone-side resize differs from the host's bicubic by about 0.004 cosine. Int8 WD-ViT embeddings measured cosine 0.9997 to float.

## Decision

- `WdTaggerRunner` runs WD ViT tagger v3 (int8 weights) on LiteRT's CPU path only, with no automatic fallback between accelerators. An accelerator would be another `PictureModel`, checked against the CPU's outputs before it is trusted. `DEFAULT_THREADS` is 4. The embedding and score outputs are told apart by length.
- `PictureModelHolder` loads the model on first use, runs one picture at a time on a fair lock (so the deck's look-ahead waits for at most one background picture), closes it `idleMs` after the last use, or at once on `release` (memory pressure, model deleted). `PictureModel` itself is not thread-safe.
- `PicturePreprocessor` centres the preview on a white square, resizes to `SIDE` with bilinear, and writes BGR planes in [-1, 1]. It reuses buffers, so one instance serves one picture at a time.
- `PictureEmbedding` stores one signed byte per dimension scaled so the largest magnitude is 127, then the scale as a little-endian float. This keeps 10,000 library posts under 8 MB.

## Consequences

Switching to a GPU or NPU delegate requires verifying outputs first. Storage format changes need a new model id.

## Applies to

- `vision-kit/src/main/kotlin/com/theoriacodex/app/vision/PictureModel.kt`: `PictureModel`, `WdTaggerRunner`, `DEFAULT_THREADS`
- `vision-kit/src/main/kotlin/com/theoriacodex/app/vision/PictureModelHolder.kt`: `PictureModelHolder`
- `vision-kit/src/main/kotlin/com/theoriacodex/app/vision/PicturePreprocessor.kt`: `PicturePreprocessor`
- `vision-kit/src/main/kotlin/com/theoriacodex/app/vision/PictureEmbedding.kt`: `PictureEmbedding`
