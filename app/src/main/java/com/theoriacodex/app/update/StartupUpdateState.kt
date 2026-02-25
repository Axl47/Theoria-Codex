package com.theoriacodex.app.update

sealed interface StartupUpdateState {
    data object Checking : StartupUpdateState
    data object NoUpdate : StartupUpdateState
    data class AwaitingUserChoice(val remote: RemoteUpdate) : StartupUpdateState
    data class Downloading(val progress: Float?) : StartupUpdateState
    data object Validating : StartupUpdateState
    data object Installing : StartupUpdateState
    data class Failed(val message: String) : StartupUpdateState
}
