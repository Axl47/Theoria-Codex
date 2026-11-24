# 0075. Picture hashes match Python's imagehash on grey input and skip flat pictures

Status: Accepted

## Context
Copies of one picture across sites are found by a 64-bit perceptual hash. The reference is Python's `imagehash.phash`, and solid-colour pictures would all share one hash.

## Decision
- `PerceptualHash` follows imagehash: grey 32x32, 2-D DCT, the 8x8 lowest frequencies, one bit per frequency against their median, first frequency as the most significant bit, so the hex string equals imagehash's. The DCT is unnormalised because a constant scale leaves the median test alone.
- `ofLuma` returns null below `MIN_LUMA_SPREAD` so flat pictures are never matched.
- `ofArgb` converts with ITU-R 601 weights (Pillow's "L") and box-averages to 32x32. Pillow resizes with Lanczos, so other sizes can differ by bits from imagehash; the app only compares hashes it made itself.
- Tests compare against strings produced by imagehash 4.3.2 on fixed 32x32 arrays, which are not resized so the DCT stage is compared.

## Consequences
Hashes are only comparable between values this app computed. Changing the grey conversion or resize invalidates stored hashes.

## Applies to
- `app-logic/src/main/kotlin/com/theoriacodex/app/media/PerceptualHash.kt`: `PerceptualHash`, `ofLuma`, `ofArgb`, `MIN_LUMA_SPREAD`
