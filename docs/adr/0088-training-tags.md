# 0088. Training tags keep each source's vocabulary and use typed taxonomy to find general tags

Status: Accepted

## Context
Recommendations must learn one vocabulary per source. Pixiv posts carry native and translated aliases that would count as separate interests, and old copies may have no taxonomy.

## Decision
- `trainingTagsFor` returns every tag a post teaches once each in the post's own order; a Like stores them all and no tag is lost to list position.
- `recommendationTaxonomyFor` uses the typed taxonomy as authority for which values are general tags. Pixiv learns its native raw tags rather than counting native and translated canonical aliases as separate interests.
- A post with no typed taxonomy (a raw-tag-only legacy copy) has only general tags.

## Consequences
Changing a source's preferred vocabulary changes what its Likes teach.

## Applies to
- `app-logic/src/main/kotlin/com/theoriacodex/app/recommend/TrainingTags.kt`: `trainingTagsFor`, `recommendationTaxonomyFor`
