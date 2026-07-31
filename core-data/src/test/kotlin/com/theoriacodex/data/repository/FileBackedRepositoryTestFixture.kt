package com.theoriacodex.data.repository

import com.theoriacodex.data.testing.RecordingIoDispatcher
import com.theoriacodex.data.testing.ControllableIoDispatcher
import com.theoriacodex.data.storage.LegacyJsonRecoveryRegistry
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.DateRange
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.PostTaxonomyTerm
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import java.io.File
import java.util.concurrent.RejectedExecutionException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

internal abstract class FileBackedRepositoryTestFixture {
    @get:Rule
    val tempFolder = TemporaryFolder()

    protected fun sampleQuery(includeTags: List<String> = listOf("landscape")): Query {
        return Query(
            mode = QueryMode.Source(SourceKey.PIXIV),
            includeTags = includeTags,
            excludeTags = emptyList(),
            sort = SortMode.TOP,
            dateRange = DateRange(fromEpochMs = 100L, toEpochMs = 200L),
            minScore = 20,
        )
    }

    protected fun tempDir(prefix: String): File {
        return tempFolder.newFolder(prefix)
    }

    protected fun samplePost(id: String, localPath: String?, source: SourceKey = SourceKey.PIXIV): Post {
        return repositoryTestPost(id = id, localPath = localPath, source = source)
    }
}
