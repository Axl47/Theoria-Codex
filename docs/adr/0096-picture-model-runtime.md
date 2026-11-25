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

## Guarded by

- `vision-kit/src/test/kotlin/com/theoriacodex/app/vision/PictureModelHolderTest.kt`: `loads once for consecutive uses and closes after the idle delay (ADR 0096)`, `a use before the delay ends restarts it (ADR 0096)`, `reopens after the idle close (ADR 0096)`, `release closes at once and the next use loads again (ADR 0096)`, `release waits for the picture under way before closing the model (ADR 0096)`, `concurrent uses run one at a time on one model (ADR 0096, 0097)`
- `vision-kit/src/test/kotlin/com/theoriacodex/app/vision/PicturePreprocessorTest.kt`: `a wide or tall picture is centred on a white square (ADR 0096)`, `argbToBgrPlanes writes blue, green then red planes scaled to -1 and 1 (ADR 0096)`
- `vision-kit/src/test/kotlin/com/theoriacodex/app/vision/PictureEmbeddingTest.kt`: `encoding is one scaled byte per dimension, then the scale as a little-endian float (ADR 0096)`, `round trip stays within one step of the largest magnitude and size is dimensions plus the scale (ADR 0096)`
- Left to prose: `WdTaggerRunner` runs native LiteRT, which no JVM test loads, so its CPU-only options and the split of its outputs by length have no host seam (a text rule over the runner would also have to allow the separate, checked accelerator model the record permits); `DEFAULT_THREADS` and the size budget are measured tuning; bilinear resizing is unguarded, since Robolectric's renderer draws the same picture with or without the filter flag; that waiters are served fairly rests on kotlinx `Mutex`; one preprocessor instance per picture is a usage rule.
