package com.theoriacodex.app.settings

internal data class CredentialAccountPresentation(
    val userIdInput: String,
    val apiKeyInput: String = "",
    val statusLabel: String,
)

internal data class ResolvedReplaceOnlyCredential(
    val userId: String,
    val apiKey: String,
)

internal fun <Credential> credentialAccountPresentation(
    credential: Credential?,
    userId: (Credential) -> String,
): CredentialAccountPresentation {
    return if (credential == null) {
        CredentialAccountPresentation(userIdInput = "", statusLabel = "Not configured")
    } else {
        CredentialAccountPresentation(
            userIdInput = userId(credential),
            statusLabel = "Configured",
        )
    }
}

internal fun credentialRecoveryPresentation(
    currentUserIdInput: String,
    statusLabel: String,
    clearUserId: Boolean,
): CredentialAccountPresentation {
    return CredentialAccountPresentation(
        userIdInput = if (clearUserId) "" else currentUserIdInput,
        statusLabel = statusLabel,
    )
}

internal fun resolveReplaceOnlyCredential(
    userIdInput: String,
    replacementApiKeyInput: String,
    configuredApiKey: String?,
): ResolvedReplaceOnlyCredential? {
    val userId = userIdInput.trim()
    val apiKey = replacementApiKeyInput.trim().ifBlank { configuredApiKey.orEmpty() }
    if (userId.isBlank() || apiKey.isBlank()) return null
    return ResolvedReplaceOnlyCredential(userId = userId, apiKey = apiKey)
}
