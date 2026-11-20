package com.theoriacodex.app.sourceauth

internal enum class PixivCallbackKind { APP, PROVIDER_NATIVE }

/** Only Pixiv's exact native handoff may omit state; similarly prefixed paths are not callbacks. */
internal fun pixivCallbackKind(scheme: String?, host: String?, path: String?): PixivCallbackKind? = when {
    scheme.equals("pixiv", true) && host.equals("account", true) && path == "/login" -> PixivCallbackKind.PROVIDER_NATIVE
    scheme.equals("theoriacodex", true) && host.equals("pixiv-auth", true) && path == "/callback" -> PixivCallbackKind.APP
    else -> null
}
