# LibreTranslate on Dokploy

This service translates only the OCR phrase a user taps. Viewer images, post URLs, tags, and
other post metadata remain on the Android device.

## Deploy

1. In Dokploy, create a Compose service using `compose.yaml`.
2. Deploy and wait for the health check to pass. The first start downloads the `en`, `ja`, `zh`,
   and `ko` Argos models into the named volume and can take several minutes.
3. In the service's Domains tab, map `translate.axor.dev` to the `libretranslate` service on port
   `5000` with HTTPS enabled and certificate type `none`.
4. Point the `translate.axor.dev` DNS record at the Dokploy server and enable Cloudflare proxying.
   The origin uses a Cloudflare Origin CA certificate, so DNS-only mode is intentionally not valid
   for Android or ordinary public TLS clients.
5. Verify the service:

   ```sh
   curl -fsS https://translate.axor.dev/health
   curl -fsS https://translate.axor.dev/translate \
     --data-urlencode 'q=こんにちは' \
     --data 'source=ja' \
     --data 'target=en' \
     --data 'format=text'
   ```

The endpoint is intentionally keyless for the personal app because an API key embedded in an
open-source APK is not a durable secret. LibreTranslate limits each client IP to 60 requests per
minute and 1,000 characters per request. Add proxy-level rate limiting before widening access.
