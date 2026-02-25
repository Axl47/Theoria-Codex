package com.theoriacodex.app.update

fun StartupUpdateState.messageText(): String {
    return when (this) {
        StartupUpdateState.Checking -> "Checking for updates..."
        StartupUpdateState.NoUpdate -> "No update found. Loading app..."
        is StartupUpdateState.AwaitingUserChoice -> "Update available (${remote.commitShaShort})"
        is StartupUpdateState.Downloading -> {
            val percentage = progress?.let { (it * 100f).toInt().coerceIn(0, 100) }
            if (percentage != null) "Update found. Downloading... $percentage%"
            else "Update found. Downloading..."
        }
        StartupUpdateState.Validating -> "Validating update..."
        StartupUpdateState.Installing -> "Opening installer..."
        is StartupUpdateState.Failed -> message
    }
}
