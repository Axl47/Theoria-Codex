# Hitomi protocol fixtures (2026-07-10)

These deterministic fixtures were captured from Hitomi's public data endpoints on 2026-07-10. They intentionally contain only provider metadata; no image, animation, or video payloads are stored.

- `global-tags.json`: `https://tagindex.hitomi.la/global/t/a/g.json`
- `artist-kio.json`: `https://tagindex.hitomi.la/artist/k/i/o.json`
- `artist-najar.nozomi.hex`: the 116-byte `https://ltn.gold-usergeneratedcontent.net/n/artist/najar-all.nozomi` list encoded as hexadecimal text
- `gallery-4042375.js`: `https://ltn.gold-usergeneratedcontent.net/galleries/4042375.js`
- `gallery-7231.js`: `https://ltn.gold-usergeneratedcontent.net/galleries/7231.js`
- `gg-shape.js`: a sanitized structural sample of `https://ltn.gold-usergeneratedcontent.net/gg.js` retaining representative shard cases, the hash-to-path function, and the captured base path

Refresh these only when a deliberate protocol change is being investigated. Default tests must remain network-independent.
