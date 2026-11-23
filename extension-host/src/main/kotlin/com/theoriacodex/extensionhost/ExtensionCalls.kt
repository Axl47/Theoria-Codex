package com.theoriacodex.extensionhost

import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.series.SeriesStatus
import com.theoriacodex.sources.http.HostOutageKind
import com.theoriacodex.sources.http.HostUnavailableException
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.model.SManga
import java.io.IOException
import java.util.Collections
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * How a source adapter runs its extension's calls, for either interface. [name] is the source's, for the log;
 * [onChallenged] hears of each host whose browser check stopped one of its calls, which can be an API, image or video
 * host besides its site's.
 */
internal class ExtensionCalls(private val name: String, private val log: (String) -> Unit, private val onChallenged: (host: String) -> Unit) {
    /**
     * Runs an extension call off the caller's thread, which is often the main one: extensions parse there and some
     * block on the network. Its failures are reported the way built-in sources report theirs, with the failure's own
     * message and never its class name, which screens would show. A [SourceAdapterException] the adapter threw
     * itself passes as it is.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun <T> calling(block: suspend () -> T): T = try {
        withContext(Dispatchers.IO) { block() }
    } catch (error: CancellationException) {
        throw error
    } catch (error: SourceAdapterException) {
        throw error
    } catch (error: Exception) {
        throw failure(error)
    } catch (error: LinkageError) {
        throw failure(error)
    }

    /** [error], thrown by extension code, as the app's failure; a browser check that caused it is reported. */
    fun failure(error: Throwable): SourceAdapterException {
        generateSequence(error) { it.cause }.firstNotNullOfOrNull { it as? HostUnavailableException }
            ?.takeIf { it.kind == HostOutageKind.CHALLENGE }
            ?.let { onChallenged(it.host) }
        val reason = when (error) {
            // A refusal (401, 403) is not reported as a missing key: the app holds no key for an extension source, and
            // would point the user at a Settings field that does not exist.
            is HttpException -> if (error.code == HTTP_TOO_MANY_REQUESTS) SourceFailureReason.RATE_LIMITED else SourceFailureReason.NETWORK
            is IOException -> SourceFailureReason.NETWORK
            is LinkageError -> {
                // The extension called a part of its interface's host API that this module does not provide.
                log("$name needs host API this module lacks: $error")
                SourceFailureReason.UNKNOWN
            }
            else -> SourceFailureReason.PARSE
        }
        return SourceAdapterException(reason, error.message, error)
    }

    private companion object {
        const val HTTP_TOO_MANY_REQUESTS = 429
    }
}

/**
 * The most recent values by address, which an adapter keeps of what its source listed: sources may keep data on them
 * between calls, so a later call about one hands the source its own object back.
 */
internal fun <T> recentlyListed(): MutableMap<String, T> = Collections.synchronizedMap(
    object : LinkedHashMap<String, T>() {
        override fun removeEldestEntry(eldest: Map.Entry<String, T>): Boolean = size > MAX_LISTED
    },
)

private const val MAX_LISTED = 2_048

/** A status number as the app's status. Aniyomi numbers its statuses as Mihon does and adds one; any other is unknown. */
internal fun seriesStatus(status: Int): SeriesStatus = when (status) {
    SManga.ONGOING -> SeriesStatus.ONGOING
    SManga.COMPLETED -> SeriesStatus.COMPLETED
    SManga.LICENSED -> SeriesStatus.LICENSED
    SManga.PUBLISHING_FINISHED -> SeriesStatus.PUBLISHING_FINISHED
    SManga.CANCELLED -> SeriesStatus.CANCELLED
    SManga.ON_HIATUS -> SeriesStatus.ON_HIATUS
    else -> SeriesStatus.UNKNOWN
}
