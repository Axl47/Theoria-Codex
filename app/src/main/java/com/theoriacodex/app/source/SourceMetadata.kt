package com.theoriacodex.app.source

import com.theoriacodex.domain.model.SourceKey

fun SourceKey.displayName(): String {
    return when (this) {
        SourceKey.PIXIV -> "Pixiv"
        SourceKey.GELBOORU -> "Gelbooru"
        SourceKey.AIBOORU -> "AIBooru"
        SourceKey.NHENTAI -> "NHentai"
        SourceKey.HITOMI -> "Hitomi"
        SourceKey.IWARA -> "Iwara"
        SourceKey.RULE34XXX -> "R34X"
        SourceKey.RULE34PAHEAL -> "R34P"
        SourceKey.RULE34VIDEO -> "R34V"
        SourceKey.RULE34GEN -> "R34G"
    }
}

fun SourceKey.requestHeaders(): Map<String, String> {
    return mapOf(
        "Referer" to referer(),
        "User-Agent" to "Mozilla/5.0",
    )
}

fun SourceKey.referer(): String {
    return when (this) {
        SourceKey.PIXIV -> "https://www.pixiv.net/"
        SourceKey.GELBOORU -> "https://gelbooru.com/"
        SourceKey.AIBOORU -> "https://aibooru.online/"
        SourceKey.NHENTAI -> "https://nhentai.net/"
        SourceKey.HITOMI -> "https://hitomi.la/"
        SourceKey.IWARA -> "https://www.iwara.tv/"
        SourceKey.RULE34XXX -> "https://rule34.xxx/"
        SourceKey.RULE34PAHEAL -> "https://rule34.paheal.net/"
        SourceKey.RULE34VIDEO -> "https://rule34video.com/"
        SourceKey.RULE34GEN -> "https://rule34gen.com/"
    }
}

fun exposedRealSources(rule34XxxConfigured: Boolean): Set<SourceKey> {
    return buildSet {
        add(SourceKey.PIXIV)
        add(SourceKey.GELBOORU)
        add(SourceKey.NHENTAI)
        add(SourceKey.IWARA)
        add(SourceKey.RULE34PAHEAL)
        add(SourceKey.RULE34VIDEO)
        add(SourceKey.RULE34GEN)
        if (rule34XxxConfigured) {
            add(SourceKey.RULE34XXX)
        }
    }
}
