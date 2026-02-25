package com.theoriacodex.app.update

interface UpdateFeedClient {
    suspend fun latestMainPrerelease(): Result<RemoteUpdate?>
}
