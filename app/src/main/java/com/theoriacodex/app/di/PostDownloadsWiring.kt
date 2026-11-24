package com.theoriacodex.app.di

import android.content.Context
import com.theoriacodex.app.media.PostDownloads
import com.theoriacodex.data.repository.SettingsRepository
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first

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
