package com.theoriacodex.sources.health

import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SourceKey
import kotlin.system.measureTimeMillis

internal class ProbeSteps(
    private val source: SourceKey,
    private val nowProvider: () -> Long,
    private val challengesSeen: () -> Int,
    private val failureReason: (Throwable) -> SourceFailureReason? = { (it as? SourceAdapterException)?.reason },
    private val includeTerms: List<SearchTerm> = emptyList(),
    private val autocompletePrefix: String? = null,
    private val diagnosticUrls: Map<String, String> = emptyMap(),
) {
    fun result(
        checkName: String,
        status: ProviderHealthStatus,
        message: String?,
        itemCount: Int? = null,
        samplePostId: String? = null,
        requestUrl: String? = null,
        autocompletePrefix: String? = this.autocompletePrefix,
    ) = ProviderProbeStepResult(
        source = source,
        checkName = checkName,
        status = status,
        latencyMs = 0L,
        message = message,
        checkedAtEpochMs = nowProvider(),
        includeTags = includeTerms.map(SearchTerm::value),
        includeTerms = includeTerms,
        autocompletePrefix = autocompletePrefix,
        itemCount = itemCount,
        samplePostId = samplePostId,
        requestUrl = requestUrl ?: diagnosticUrls[checkName],
    )

    suspend fun run(
        checkName: String,
        autocompletePrefix: String? = this.autocompletePrefix,
        requestUrl: () -> String? = { diagnosticUrls[checkName] },
        block: suspend () -> ProviderProbeStepResult,
    ): ProviderProbeStepResult = observingChallenges(challengesSeen) {
        val outcome: Result<ProviderProbeStepResult>
        val latencyMs = measureTimeMillis { outcome = runCatchingPreservingCancellation { block() } }
        outcome.fold(
            onSuccess = { it.copy(latencyMs = latencyMs) },
            onFailure = { error ->
                result(checkName, ProviderHealthStatus.FAILED, error.message ?: error::class.simpleName)
                    .copy(
                        latencyMs = latencyMs,
                        failureReason = failureReason(error),
                        autocompletePrefix = autocompletePrefix,
                        requestUrl = requestUrl(),
                    )
            },
        )
    }
}

private suspend fun observingChallenges(
    challengesSeen: () -> Int,
    step: suspend () -> ProviderProbeStepResult,
): ProviderProbeStepResult {
    val challengesBefore = challengesSeen()
    val result = step()
    val isProblem = result.status == ProviderHealthStatus.FAILED || result.status == ProviderHealthStatus.DEGRADED
    if (!isProblem || challengesSeen() == challengesBefore) return result
    return result.copy(
        status = ProviderHealthStatus.CANNOT_CHECK,
        failureReason = null,
        message = "A browser challenge answered instead of the provider",
    )
}
