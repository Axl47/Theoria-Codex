package com.theoriacodex.app.appshell

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class IncomingUriKind {
    PIXIV_AUTH_CALLBACK,
    CODEX_IMPORT,
    EXTERNAL_CONTENT,
}

internal data class PendingIncomingUri(
    val value: String,
    val kind: IncomingUriKind,
)

/** Owns a single pending external handoff across Activity recreation and process restoration. */
internal class IncomingUriWorkflow(
    private val savedStateHandle: SavedStateHandle,
) {
    private val mutablePending = MutableStateFlow(restore())

    val pending: StateFlow<PendingIncomingUri?> = mutablePending.asStateFlow()

    fun accept(
        uri: String,
        isPixivAuthorizationCallback: Boolean,
        isCodexImport: Boolean,
    ): PendingIncomingUri {
        val incoming = PendingIncomingUri(
            value = uri,
            kind = when {
                isPixivAuthorizationCallback -> IncomingUriKind.PIXIV_AUTH_CALLBACK
                isCodexImport -> IncomingUriKind.CODEX_IMPORT
                else -> IncomingUriKind.EXTERNAL_CONTENT
            },
        )
        savedStateHandle[URI_KEY] = incoming.value
        savedStateHandle[KIND_KEY] = incoming.kind.name
        mutablePending.value = incoming
        return incoming
    }

    fun consume(expected: PendingIncomingUri) {
        if (mutablePending.value != expected) return
        savedStateHandle[URI_KEY] = null
        savedStateHandle[KIND_KEY] = null
        mutablePending.value = null
    }

    private fun restore(): PendingIncomingUri? {
        val uri = savedStateHandle.get<String>(URI_KEY)?.takeIf(String::isNotBlank) ?: return null
        val kind = savedStateHandle.get<String>(KIND_KEY)
            ?.let { stored -> IncomingUriKind.entries.firstOrNull { it.name == stored } }
            ?: IncomingUriKind.EXTERNAL_CONTENT
        return PendingIncomingUri(value = uri, kind = kind)
    }

    private companion object {
        const val URI_KEY = "pending_incoming_uri"
        const val KIND_KEY = "pending_incoming_uri_kind"
    }
}
