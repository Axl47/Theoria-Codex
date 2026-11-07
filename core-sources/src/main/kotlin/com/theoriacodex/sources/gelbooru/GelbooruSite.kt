package com.theoriacodex.sources.gelbooru

import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.credentials.ApiKeyUse
import com.theoriacodex.sources.rule34.RULE34_BROWSER_HEADERS

/**
 * One site running the Gelbooru 0.2 DAPI (`index.php?page=dapi`). Every field is plain data, so a
 * user-added site can be described the same way as the built-in presets; defaults follow what most
 * Gelbooru 0.2 sites accept. Post and tag lists are JSON (`json=1`), either a bare array or wrapped
 * as `{"post": [...]}` / `{"tag": [...]}`; the adapter accepts both, so the wrapper needs no field.
 */
data class GelbooruSite(
    val source: SourceKey,
    /** Name used in failure messages. */
    val label: String,
    /** Site root for post, uploader and related-post pages. */
    val siteUrl: String,
    /** DAPI endpoint; some sites serve the API from a separate host. */
    val apiUrl: String = "$siteUrl/index.php",
    /** `autocomplete.php`-style endpoint returning `[{label, value}]`; null searches the DAPI tag list instead. */
    val autocompleteUrl: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val credentials: ApiKeyUse = ApiKeyUse.OPTIONAL,
    val postLookup: GelbooruPostLookup = GelbooruPostLookup.ID_PARAMETER,
    /** Native spelling of an OR group; null makes the search orchestrator expand groups instead. */
    val orGroup: GelbooruOrGroup? = null,
    /** Appended to newest-first searches; null relies on the site's default id order. */
    val newestSortTag: String? = null,
    val scoreSortTag: String = "sort:score:desc",
    /** The post's `sample_url` is a lighter viewer rendition to load before the original. */
    val progressiveSample: Boolean = false,
    /** Uploader (`owner`) becomes a followable creator whose posts are searched with `user:<owner>`. */
    val creatorPosts: Boolean = false,
    /** The HTML post page's "More Like This:" block supplies related posts. */
    val relatedPosts: Boolean = false,
    val tagList: GelbooruTagList = GelbooruTagList.JSON,
) {
    companion object {
        val GELBOORU = GelbooruSite(
            source = SourceKey.GELBOORU,
            label = "Gelbooru",
            siteUrl = "https://gelbooru.com",
            orGroup = GelbooruOrGroup(prefix = "{", separator = " ~ ", suffix = "}"),
            newestSortTag = "sort:id:desc",
            progressiveSample = true,
            creatorPosts = true,
            relatedPosts = true,
        )

        /** The API host differs from the site, every API call needs a key, and tags come from `autocomplete.php`. */
        val RULE34XXX = GelbooruSite(
            source = SourceKey.RULE34XXX,
            label = "rule34.xxx",
            siteUrl = "https://rule34.xxx",
            apiUrl = "https://api.rule34.xxx/index.php",
            autocompleteUrl = "https://api.rule34.xxx/autocomplete.php",
            headers = RULE34_BROWSER_HEADERS,
            credentials = ApiKeyUse.REQUIRED,
            postLookup = GelbooruPostLookup.ID_TAG,
            scoreSortTag = "sort:score",
        )
    }
}

enum class GelbooruPostLookup {
    /** `id=<id>` on the post list. */
    ID_PARAMETER,

    /** `tags=id:<id>` on the post list. */
    ID_TAG,
}

/** How the DAPI tag list (`s=tag`) answers, which decides where trending, autocomplete and counts come from. */
enum class GelbooruTagList {
    /** Honors `json=1`, `names`, `name_pattern` and `orderby`. */
    JSON,

    /**
     * Unknown until the first tag request. Some forks (Safebooru) answer the tag list in XML whatever
     * `json=1` says and ignore its filters; the first XML answer switches the adapter to
     * `autocomplete.php` for suggestions and counts and turns trending off for its lifetime.
     */
    DETECT,
}

/** Wraps OR alternatives, e.g. `{cat ~ dog}` on Gelbooru or `( cat ~ dog )` on older forks. */
data class GelbooruOrGroup(val prefix: String, val separator: String, val suffix: String)
