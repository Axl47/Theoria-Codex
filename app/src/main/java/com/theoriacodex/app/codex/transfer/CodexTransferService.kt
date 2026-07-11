package com.theoriacodex.app.codex.transfer

import com.google.gson.Gson
import com.theoriacodex.app.codex.buildCodexShareFile
import com.theoriacodex.app.codex.parseCodexShareFile
import com.theoriacodex.app.codex.resolveCodexShareImportPost
import com.theoriacodex.app.codex.sanitizeCodexExportName
import com.theoriacodex.app.codex.selectCodexShareEntries
import com.theoriacodex.data.repository.CacheRepository
import com.theoriacodex.data.repository.CodexRepository
import com.theoriacodex.data.repository.CodexSortMode
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import kotlinx.coroutines.flow.first

data class CodexExportPayload(
    val title: String,
    val fileName: String,
    val json: String,
)

sealed interface CodexExportResult {
    data class Success(val payload: CodexExportPayload) : CodexExportResult
    data object NotFound : CodexExportResult
}

sealed interface CodexImportResult {
    data object Unreadable : CodexImportResult
    data object Invalid : CodexImportResult
    data class Success(
        val codexId: String,
        val imported: Int,
        val skipped: Int,
    ) : CodexImportResult
}

/** Platform-free Codex JSON transfer; ContentResolver/FileProvider remain shell effects. */
class CodexTransferService internal constructor(
    private val codexRepository: CodexRepository,
    private val cacheRepository: CacheRepository,
    private val sourceRegistry: SourceAdapterRegistry,
    private val gson: Gson = Gson(),
) {
    suspend fun export(codexId: String): CodexExportResult {
        val codex = codexRepository.observeCodex(codexId).first() ?: return CodexExportResult.NotFound
        val posts = codexRepository.observeCodexPosts(codexId, CodexSortMode.NEWEST_SAVED).first()
        return CodexExportResult.Success(
            CodexExportPayload(
                title = codex.name,
                fileName = "${sanitizeCodexExportName(codex.name)}.json",
                json = gson.toJson(buildCodexShareFile(title = codex.name, posts = posts)),
            )
        )
    }

    suspend fun import(
        raw: String?,
        targetCodexId: String,
    ): CodexImportResult {
        if (raw.isNullOrBlank()) return CodexImportResult.Unreadable
        val parsed = parseCodexShareFile(raw) ?: return CodexImportResult.Invalid
        val title = parsed.title?.trim().orEmpty()
        if (title.isBlank()) return CodexImportResult.Invalid

        val target = codexRepository.ensureCodex(codexId = targetCodexId, name = title)
        val entries = selectCodexShareEntries(parsed.posts.orEmpty())
        var imported = 0
        entries.forEach { (entry, postId) ->
            val sourcePost = sourceRegistry.adapterFor(postId.source)?.let { adapter ->
                runCatchingPreservingCancellation { adapter.resolvePost(postId) }.getOrNull()
            }
            val resolved = resolveCodexShareImportPost(
                entry = entry,
                resolvedFromSource = sourcePost,
                storedPost = { codexRepository.getPost(postId) },
            ) ?: return@forEach
            codexRepository.addItem(target.codexId, resolved)
            cacheRepository.cacheThumbnail(resolved)
            imported += 1
        }
        return CodexImportResult.Success(
            codexId = target.codexId,
            imported = imported,
            skipped = entries.size - imported,
        )
    }
}
