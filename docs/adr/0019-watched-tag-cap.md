# 0019. Statistics keep each source's 2,000 most watched tags

Status: Accepted

## Context

Every viewed post adds all its tags to `watchedByTag` in `statistics_store_v1.json`. The whole file is rewritten on each view, and tag affinity groups every entry again whenever statistics change. Over months, tags seen once or twice make up most of the map while barely moving any score.

## Decision

Once a source holds more than 2,500 watched tags, it keeps only its 2,000 with the highest counts, ties broken by tag. The cap is applied in `StatisticsPolicies.normalize`, which runs on every write and every load, so existing files are capped too. The gap between 2,000 and 2,500 means the sort runs about once per 500 new tags rather than on every view. Restoring a backup caps at 2,000 outright, so restoring the same backup twice changes nothing. `watchedBySource` stays exact.

## Consequences

The file and the affinity rebuild stop growing with every new tag. A dropped tag reads as never watched, so its lift is computed from the pseudo-count. That only changes tags rarer than the source's 2,000th, but it can raise such a tag's lift past the cutoff and make it a proven interest: with 1,000 watched and 600 kept posts, a tag on three watched and two kept posts goes from a lift of 1.39 to 8.32. A new tag has to outrun the lowest kept counts within those 500 additions to stay. To revisit, change `WATCHED_TAGS_KEPT` and `WATCHED_TAGS_PRUNE_AT`.

## Applies to

- `core-data/src/main/kotlin/com/theoriacodex/data/repository/StatisticsRepositories.kt`: `keepTopTagsPerSource`, `StatisticsPolicies.mergeRestored`, `WATCHED_TAGS_KEPT`, `WATCHED_TAGS_PRUNE_AT`
