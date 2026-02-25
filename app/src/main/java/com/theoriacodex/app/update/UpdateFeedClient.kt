package com.theoriacodex.app.update

interface UpdateFeedClient {
    suspend fun latestMainPrerelease(): Result<RemoteUpdate?>
    suspend fun mainPrereleaseHistory(limit: Int = 20): Result<List<RemoteUpdate>>
}
