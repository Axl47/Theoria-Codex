# Google Cloud Translation on Dokploy

This deployment exposes Theoria's batch-only `POST /translate-batch` endpoint backed by Google
Cloud Translation's standard NMT model. The gateway accepts only up to 32 exact detected phrases,
one `ja`/`zh-Hans`/`ko` source code, English target, and text format; it maps `zh-Hans` to Google's
`zh-CN` code and returns one ordered `translations` array.

The directory keeps its legacy `libretranslate` name, and the public service retains that name,
because installed builds and the existing Dokploy domain route depend on the compatibility
contract—not because LibreTranslate still runs.

## Deploy

1. Build the checked-in gateway on the Dokploy host:

   ```sh
   docker build \
     --tag theoriacodex/translation-gateway:google-batch-v1 \
     --file Dockerfile.gateway \
     .
   ```

   Dokploy's Raw Compose provider replaces its working directory before every deployment, so the
   stored Compose uses the prebuilt local image with `pull_policy: never` instead of `build: .`.

2. In the Dokploy Environment tab, set `GOOGLE_TRANSLATE_API_KEY`. Restrict the Google Cloud key
   to the Cloud Translation API and the Dokploy host's public IPv4 address. Never put the value in
   this repository, the Compose source, deployment logs, or the Android APK.

3. Store `compose.yaml` as the service's Raw Compose definition. Keep the existing domain mapped
   to service `libretranslate` on port `5000`; the deterministic Traefik labels also preserve
   `translate.axor.dev` during direct recovery.

4. Deploy and verify the batch-only contract:

   ```sh
   curl -fsS https://translate.axor.dev/health
   curl -fsS https://translate.axor.dev/translate-batch \
     --header 'Content-Type: application/json' \
     --data '{"q":["こんにちは","ありがとう"],"source":"ja","target":"en","format":"text"}'
   ```

The public gateway accepts one batch at a time, limits work to 60 requests per minute, 15,000
characters per rolling day, 32 phrases, 1,000 characters per phrase, and 5,000 characters per
batch, caps Google responses at 64 KiB, and never logs request bodies. It
places the API key only in the `X-Goog-Api-Key` header. The service remains intentionally keyless
to the Android client because a credential embedded in an open-source APK is not durable.

The project owner obtained written approval for Theoria's adult-content use and attribution
exception. Retain that approval outside the repository and re-check it before changing where or
how Google translation results are presented.
