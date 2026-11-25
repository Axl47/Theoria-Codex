# 0086. Statistics keep every recorded post, avoid reading posts on timer ticks, and attribute only direct Codex launches

Status: Accepted

## Context
Usage time updates every tick, while content statistics depend on stored posts and shared copies can leave a post out.

## Decision
- `CodexStatisticsSummary` keeps every recorded post so a shared copy can leave one out and rerank the rest.
- `SavedContentStatistics` is a process-only projection; timer updates never read Posts, and `withLiveUsage` overlays live monotonic time without rebuilding content, source or tag statistics.
- Only direct Codex launches carry collection attribution (`statisticsCodexId`); Recents keeps its own launch identity.

## Consequences
Opening a post from Recents never counts toward a Codex.

## Applies to
- `app-logic/src/main/kotlin/com/theoriacodex/app/statistics/StatisticsProjection.kt`: `CodexStatisticsSummary`, `SavedContentStatistics`, `withLiveUsage`, `statisticsCodexId`

## Guarded by

- `app-logic/src/test/kotlin/com/theoriacodex/app/statistics/StatisticsProjectionTest.kt`: `a Codex summary keeps every recorded post beyond its top five (ADR 0086)`, `only a launch from a Codex carries a Codex id (ADR 0086)`
- Left to prose: that timer updates never read posts and `withLiveUsage` rebuilds no content, source or tag statistics is not yet guarded; testable in `:app`, in the statistics source (`SettingsStatisticsSourceTest` is the nearest existing test).
