# Theoria Codex

A local-first Android app for browsing, collecting, and rediscovering media from multiple art and gallery sites in one place.

You search across sources (one at a time or all together), open posts in a full-screen viewer, and save what you like into personal collections called **Codices**. Your likes and tag history drive a **For You** feed. Everything (collections, likes, history, preferences) stays on your device. There are no accounts or cloud sync.

## What it does

- **Search**: tag-based search per source or across all of them (Unified), with include/exclude terms, autocomplete, saved tags, and filters like *Animated only*, *Hide liked*, and *Hide saved*.
- **Viewer**: images, video, GIFs, animated WebP, Pixiv ugoira, and multi-page galleries, with gesture seek, brightness and volume controls, playback speed, quality selection, and picture-in-picture.
- **Codex**: local collections you can sort, export or import as JSON, make available offline, or save to the device.
- **For You**: recommendations from your liked posts and tag affinity, with blacklists and separate profiles.
- **Recents**: watch and search history, with named pinned searches and gallery resume.
- **Creators**: follow creators locally and check for new posts on demand.
- **Backup**: export and restore profiles, collections, likes, and preferences. Credentials and media are never included.

## Sources

Pixiv · Gelbooru · Danbooru (guests can search two tags at a time) · AIBooru · NHentai · Hitomi · Iwara · Rule34 Paheal · Rule34 Video · Rule34 Gen · Rule34.xxx (needs an API key in Settings)

## Platform

Android 17 and 16 only (min API 36, target API 37). Release builds update themselves from GitHub prereleases. Debug builds install separately as **Theoria Debug**.

## Project layout

| Module | Role |
| --- | --- |
| `app` | Compose UI, navigation, ViewModels, app-level coordinators |
| `app-logic` | Platform-free search, feed, media, and recommendation policy |
| `core-domain` | Domain models, source contracts, and query state |
| `core-data` / `core-data-android` | Repositories, DataStore settings, and Room storage |
| `core-sources` | Real provider integrations and HTTP |
| `core-stubs` | Fixture sources for deterministic tests |
| `baseline-profile`, `macrobenchmark` | Startup profiles and performance measurement |

## Building

Use **JDK 21** to run Gradle (Robolectric requires it) and keep JDK 17 installed for the JVM modules.

```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest :app-logic:test :core-domain:test :core-data:test \
  :core-data-android:testDebugUnitTest :core-stubs:test :core-sources:test
```

## More docs

- [.docs/DEVELOPMENT.md](.docs/DEVELOPMENT.md): architecture, storage layout, releases, device and performance testing, live provider checks, and copying a personal profile into Debug
- [CHANGELOG.md](CHANGELOG.md): user-facing changes
- [.docs/PLANS.md](.docs/PLANS.md): ExecPlan standard (plans live in `.docs/exec/`)
