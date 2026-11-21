package com.theoriacodex.sources.health

import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SourceKey
import kotlin.system.measureTimeMillis

/**
 * Builds and times the steps of one probe target, for [ProviderProbeRunner] and
 * [HitomiProviderHealthProbe]. Every result names [source], [includeTerms] and, unless the step
 * names its own, [autocompletePrefix]; a step that reports no request URL reports its
 * [diagnosticUrls] entry.
 */
internal class ProbeSteps(
    private val source: SourceKey,
    private val nowProvider: () -> Long,
    private val challengesSeen: () -> Int,
    private val failureReason: (Throwable) -> SourceFailureReason? = { (it as? SourceAdapterException)?.reason },
    private val includeTerms: List<SearchTerm> = emptyList(),
    private val autocompletePrefix: String? = null,
    private val diagnosticUrls: Map<String, String> = emptyMap(),
) {
    /** A step outcome stamped now; [run] fills in its latency. */
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

    /**
     * Runs [block] as step [checkName] and records its latency. A thrown failure becomes a FAILED
     * result reporting [requestUrl], read after the step so it can name the request that failed;
     * cancellation propagates.
     */
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

/**
 * Runs one probe step. A problem during which a browser challenge answered says nothing about the
 * provider, so it is reported as [ProviderHealthStatus.CANNOT_CHECK].
 */
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
