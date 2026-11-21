package com.theoriacodex.app.di

import android.content.Context
import com.theoriacodex.app.media.PostDownloads
import com.theoriacodex.data.repository.SettingsRepository
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first

/**
 * Builds the app's one [PostDownloads] over [jobFile]; each app container calls this once, so every Activity shares
 * it. Posts resolve through [sources]' registry and Pixiv animations export through its Ugoira client. Downloads
 * start only after [sourcesReady]: the container's readiness, when site adapters and saved browser headers are set.
 */
internal fun appPostDownloads(
    context: Context,
    jobFile: File,
    scope: CoroutineScope,
    settings: SettingsRepository,
    sources: SourceDependencies,
    sourcesReady: suspend () -> Unit,
): PostDownloads = PostDownloads(
    context = context,
    jobFile = jobFile,
    parentScope = scope,
    readSettings = { settings.observeSettings().first().cache },
    resolvePost = { post -> sources.registry.adapterFor(post.id.source)?.resolvePost(post.id) },
    exportAnimation = { post, marker ->
        sources.pixivUgoiraClient.exportToMp4(context, post.id.sourcePostId, post.title, marker).map { }
    },
    sourcesReady = sourcesReady,
)
