package com.theoriacodex.app.series

import com.theoriacodex.domain.coroutines.runCatchingExceptionsPreservingCancellation
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

internal class PagedListing(
    private val scope: CoroutineScope,
    private val read: () -> SeriesListing?,
    private val show: (SeriesListing) -> Unit,
) {
    private var pages: (suspend (Int) -> SeriesPage)? = null
    private var load: Job? = null

    fun start(pages: suspend (Int) -> SeriesPage) {
        this.pages = pages
        settle { firstPage { pages(1) } }
    }

    fun resume(pages: suspend (Int) -> SeriesPage) {
        cancel()
        this.pages = pages
    }

    fun more() {
        val loaded = read() as? SeriesListing.Loaded ?: return
        val page = loaded.nextPage?.takeUnless { loaded.loadingMore } ?: return
        val pages = pages ?: return
        show(loaded.copy(loadingMore = true, moreFailed = false))
        settle { loaded.withPage(page, pages) }
    }

    fun launch(block: suspend CoroutineScope.() -> Unit) {
        cancel()
        val next = scope.launch(start = CoroutineStart.LAZY, block = block)
        load = next
        next.start()
    }

    fun cancel() {
        load?.cancel()
        load = null
    }

    private fun settle(answer: suspend () -> SeriesListing) = launch {
        val listing = answer()
        ensureActive()
        show(listing)
    }
}

internal suspend fun firstPage(load: suspend () -> SeriesPage): SeriesListing = runCatchingExceptionsPreservingCancellation {
    load().let { SeriesListing.Loaded(it.series, nextPage = 2.takeIf { _ -> it.hasNextPage }) }
}.getOrElse { error ->
    SeriesListing.Failed(failureLine(error))
}

private suspend fun SeriesListing.Loaded.withPage(page: Int, load: suspend (Int) -> SeriesPage): SeriesListing.Loaded =
    runCatchingExceptionsPreservingCancellation {
        val more = load(page)
        copy(
            series = (series + more.series).distinctBy(Series::id),
            nextPage = (page + 1).takeIf { more.hasNextPage },
            loadingMore = false,
            moreFailed = false,
        )
    }.getOrElse {
        copy(loadingMore = false, moreFailed = true)
    }
