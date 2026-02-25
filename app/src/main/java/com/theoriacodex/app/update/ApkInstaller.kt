package com.theoriacodex.app.update

import java.io.File

interface ApkInstaller {
    fun launchInstaller(apkFile: File): Result<Unit>
}

class UnknownSourcesPermissionRequiredException(
    message: String = "Unknown sources permission is not granted",
) : IllegalStateException(message)
