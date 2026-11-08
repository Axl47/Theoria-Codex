package com.theoriacodex.sources.health

import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderHealthCheckCliTest {
    @Test
    fun `a browser challenge only marks a source when nothing is actually broken`() {
        val cannotCheck = step(SourceKey.IWARA, ProviderHealthStatus.CANNOT_CHECK)
        val ok = step(SourceKey.IWARA, ProviderHealthStatus.OK)
        val failed = step(SourceKey.IWARA, ProviderHealthStatus.FAILED)

        assertEquals(ProviderHealthStatus.CANNOT_CHECK, aggregateProbeResults(listOf(ok, cannotCheck)).single().status)
        assertEquals(ProviderHealthStatus.FAILED, aggregateProbeResults(listOf(failed, cannotCheck)).single().status)
    }

    @Test
    fun `source filter selects only requested provider cases`() {
        val selected = selectProbeCases(
            cases = ProviderProbeCases.defaults,
            requestedSources = parseRequestedSources(" hitomi "),
        )

        assertEquals(listOf(SourceKey.HITOMI), selected.map(ProviderProbeCase::source))
    }

    @Test
    fun `source filter rejects unknown or unconfigured providers`() {
        val unknown = runCatching { parseRequestedSources("missing") }.exceptionOrNull()
        val unconfigured = runCatching {
            selectProbeCases(
                cases = listOf(ProviderProbeCase(source = SourceKey.HITOMI)),
                requestedSources = setOf(SourceKey.PIXIV),
            )
        }.exceptionOrNull()

        assertTrue(unknown is IllegalArgumentException)
        assertTrue(unconfigured is IllegalArgumentException)
    }

    @Test
    fun `strict targeted health fails every non ok selected step`() {
        val ok = reportWith(
            step(SourceKey.HITOMI, ProviderHealthStatus.OK),
        )
        val degraded = reportWith(
            step(SourceKey.HITOMI, ProviderHealthStatus.OK),
            step(SourceKey.HITOMI, ProviderHealthStatus.DEGRADED),
        )
        val skipped = reportWith(
            step(SourceKey.HITOMI, ProviderHealthStatus.SKIPPED),
        )

        assertFalse(shouldFailStrict(ok, setOf(SourceKey.HITOMI)))
        assertTrue(shouldFailStrict(degraded, setOf(SourceKey.HITOMI)))
        assertTrue(shouldFailStrict(skipped, setOf(SourceKey.HITOMI)))
        assertTrue(shouldFailStrict(reportWith(), setOf(SourceKey.HITOMI)))
    }

    @Test
    fun `untargeted strict health retains failed only behavior`() {
        assertFalse(
            shouldFailStrict(
                reportWith(step(SourceKey.HITOMI, ProviderHealthStatus.DEGRADED)),
                emptySet(),
            ),
        )
        assertTrue(
            shouldFailStrict(
                reportWith(step(SourceKey.HITOMI, ProviderHealthStatus.FAILED)),
                emptySet(),
            ),
        )
    }

    @Test
    fun `public safe summary lists only source status and reason enum`() {
        val report = ProviderHealthReport(
            liveProvidersEnabled = true,
            generatedAtEpochMs = 1L,
            results = listOf(
                ProviderHealthCheckResult(
                    source = SourceKey.HITOMI,
                    checkName = "source-probe",
                    status = ProviderHealthStatus.FAILED,
                    latencyMs = 987L,
                    failureReason = SourceFailureReason.PARSE,
                    message = "secret-message https://example.com/?api_key=abc sample 4042375",
                    checkedAtEpochMs = 1L,
                ),
                ProviderHealthCheckResult(
                    source = SourceKey.DANBOORU,
                    checkName = "source-probe",
                    status = ProviderHealthStatus.OK,
                    latencyMs = 5L,
                    checkedAtEpochMs = 1L,
                ),
            ),
            probeResults = listOf(step(SourceKey.HITOMI, ProviderHealthStatus.FAILED)),
        )

        assertEquals(
            listOf("HITOMI: FAILED PARSE", "DANBOORU: OK NONE"),
            publicSafeSummaryLines(report),
        )
    }

    private fun reportWith(vararg results: ProviderProbeStepResult): ProviderHealthReport {
        return ProviderHealthReport(
            liveProvidersEnabled = true,
            generatedAtEpochMs = 1L,
            results = emptyList(),
            probeResults = results.toList(),
        )
    }

    private fun step(
        source: SourceKey,
        status: ProviderHealthStatus,
    ): ProviderProbeStepResult {
        return ProviderProbeStepResult(
            source = source,
            checkName = "test-step",
            status = status,
            latencyMs = 0L,
            checkedAtEpochMs = 1L,
            requestUrl = "https://example.com/test",
        )
    }
}
