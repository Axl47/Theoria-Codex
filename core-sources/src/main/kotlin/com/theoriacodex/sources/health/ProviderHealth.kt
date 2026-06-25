package com.theoriacodex.sources.health

import com.theoriacodex.domain.adapter.QuickQueryKind
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.model.SourceKey
import kotlin.system.measureTimeMillis

enum class ProviderHealthStatus {
    OK,
    DEGRADED,
    FAILED,
    SKIPPED,
}

data class ProviderHealthCheckResult(
    val source: SourceKey,
    val checkName: String,
    val status: ProviderHealthStatus,
    val latencyMs: Long,
    val failureReason: SourceFailureReason? = null,
    val message: String? = null,
    val checkedAtEpochMs: Long,
)

data class ProviderHealthReport(
    val liveProvidersEnabled: Boolean,
    val generatedAtEpochMs: Long,
    val results: List<ProviderHealthCheckResult>,
)

class ProviderHealthChecker(
    private val registry: SourceAdapterRegistry,
    private val nowProvider: () -> Long = System::currentTimeMillis,
) {
    suspend fun checkAll(sources: Collection<SourceKey> = registry.availableSources()): List<ProviderHealthCheckResult> {
        return sources.sortedBy { it.name }.map { source ->
            val adapter = registry.adapterFor(source)
            if (adapter == null) {
                ProviderHealthCheckResult(
                    source = source,
                    checkName = CHECK_NAME,
                    status = ProviderHealthStatus.SKIPPED,
                    latencyMs = 0L,
                    message = "Source is not exposed by this registry",
                    checkedAtEpochMs = nowProvider(),
                )
            } else {
                check(adapter)
            }
        }
    }

    private suspend fun check(adapter: SourceAdapter): ProviderHealthCheckResult {
        var failure: Throwable? = null
        var itemCount = 0
        val latency = measureTimeMillis {
            try {
                val query = adapter.quickQuery(QuickQueryKind.NEWEST)
                val page = adapter.search(query, pageToken = null)
                itemCount = page.items.size
            } catch (error: Throwable) {
                failure = error
            }
        }
        val checkedAt = nowProvider()
        val caught = failure
        if (caught != null) {
            val typed = caught as? SourceAdapterException
            return ProviderHealthCheckResult(
                source = adapter.sourceKey,
                checkName = CHECK_NAME,
                status = ProviderHealthStatus.FAILED,
                latencyMs = latency,
                failureReason = typed?.reason,
                message = caught.message ?: caught::class.simpleName,
                checkedAtEpochMs = checkedAt,
            )
        }
        return ProviderHealthCheckResult(
            source = adapter.sourceKey,
            checkName = CHECK_NAME,
            status = if (itemCount > 0) ProviderHealthStatus.OK else ProviderHealthStatus.DEGRADED,
            latencyMs = latency,
            message = if (itemCount > 0) "Returned $itemCount posts" else "Reachable but returned no posts",
            checkedAtEpochMs = checkedAt,
        )
    }

    private companion object {
        const val CHECK_NAME = "newest-search"
    }
}
