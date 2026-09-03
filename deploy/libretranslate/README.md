# Hy-MT2 translation on Dokploy

This deployment replaces LibreTranslate while retaining its narrow `POST /translate` wire format,
so existing Theoria builds continue to work. A small gateway accepts only the tapped OCR phrase,
`ja`/`zh-Hans`/`ko` source code, English target, and text format. It sends that phrase to a private
CPU-only `tencent/Hy-MT2-1.8B-GGUF:Q4_K_M` llama.cpp service and returns `translatedText`.

## Deploy

1. In the existing Dokploy Compose service, replace the Compose definition and add both
   Dockerfiles plus `gateway.py` from this directory. The Hy-MT2 image builds from the pinned
   official llama.cpp `b10775` Ubuntu binary because GHCR denies anonymous pulls on this VPS.
2. Keep the existing domain mapped to service `libretranslate` on port `5000`. The legacy service
   name and deterministic Traefik labels preserve Dokploy's `translate.axor.dev` route even when
   Compose is invoked directly for recovery.
3. Deploy and wait for the gateway health check to pass. The first start downloads the 1.13 GB
   Q4_K_M model into the persistent `hymt2-models` volume.
4. Verify the bounded public contract:

   ```sh
   curl -fsS https://translate.axor.dev/health
   curl -fsS https://translate.axor.dev/translate \
     --data-urlencode 'q=こんにちは' \
     --data 'source=ja' \
     --data 'target=en' \
     --data 'format=text'
   ```

The model endpoint is reachable only on the internal Compose network. The public gateway accepts
one inference at a time, limits work to 60 requests per minute and 1,000 characters per phrase,
caps its model response, and never logs request bodies. The service is intentionally keyless
because an API key embedded in an open-source APK is not a durable secret.

The llama.cpp container is capped at 2.25 GiB RAM and three CPU cores so it cannot consume the
entire shared VPS. Q4_K_M is intentional: the lower-bit variants save memory at the expense of the
translation nuance this deployment exists to improve.
