package com.theoriacodex.extensionhost

import com.theoriacodex.domain.series.SeriesKind
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber

data class RepoIndex(val name: String, val signingKey: String, val extensions: List<IndexedExtension>)

data class IndexedExtension(
    val name: String,
    val packageName: String,
    val apkUrl: String,
    val libVersion: String,
    val versionCode: Long,
    val versionName: String,
    val languages: List<String>,
    val kind: SeriesKind,
    val torrent: Boolean = false,
)

@OptIn(ExperimentalSerializationApi::class)
internal object RepoReader {
    suspend fun read(url: String, get: suspend (String) -> ByteArray): RepoIndex = read(fetchAddress(url), get, followed = false)

    fun fetchAddress(url: String): String {
        val path = url.trimEnd('/').substringAfter("://").substringAfter('/', missingDelimiterValue = "")
        return if ('.' in path.substringAfterLast('/')) url else url.trimEnd('/') + "/repo.json"
    }

    private suspend fun read(address: String, get: suspend (String) -> ByteArray, followed: Boolean): RepoIndex {
        val body = gunzipped(get(address))
        return when (body.firstOrNull { !it.toInt().toChar().isWhitespace() }?.toInt()?.toChar()) {
            '[' -> {
                if (!address.endsWith("/index.min.json")) throw SerializationException("A list of extensions, not a repo")
                read(address.removeSuffix("/index.min.json") + "/repo.json", get, followed)
            }
            '{' -> {
                val repo = json.decodeFromString(LegacyRepo.serializer(), body.decodeToString())
                val next = repo.indexV2
                if (next != null && !followed) return read(next, get, followed = true)
                val folder = address.substringBeforeLast('/')
                val list = json.decodeFromString(LegacyList, gunzipped(get("$folder/index.min.json")).decodeToString())
                RepoIndex(repo.meta.name, repo.meta.signingKeyFingerprint.lowercase(), list.map { it.indexed(folder) })
            }
            else -> {
                val anime = runCatching { protoStore(body, AnimeEntry.serializer(), get) }
                    .onFailure { if (it !is SerializationException && it !is IllegalArgumentException) throw it }
                    .getOrNull()
                val store = anime?.takeUnless { (_, _, entries) -> entries.any { '.' in it.extensionLib } } ?: protoStore(body, MangaEntry.serializer(), get)
                RepoIndex(store.first, store.second.lowercase(), store.third.map(ProtoEntry::indexed))
            }
        }
    }

    private suspend fun <E : ProtoEntry> protoStore(body: ByteArray, entry: KSerializer<E>, get: suspend (String) -> ByteArray): Triple<String, String, List<E>> {
        val store = protobuf.decodeFromByteArray(ProtoStore.serializer(entry), body)
        val list = store.extensionList ?: store.extensionListUrl?.let { protobuf.decodeFromByteArray(ProtoList.serializer(entry), gunzipped(get(it))) }
            ?: throw SerializationException("The repo lists no extensions")
        return Triple(store.name, store.signingKey, list.extensions)
    }

    private fun gunzipped(body: ByteArray): ByteArray =
        if (body.size >= 2 && body[0] == GZIP_FIRST && body[1] == GZIP_SECOND) GZIPInputStream(ByteArrayInputStream(body)).use { readAtMost(it, MAX_INDEX_BYTES) } else body

    private const val GZIP_FIRST = 0x1f.toByte()
    private const val GZIP_SECOND = 0x8b.toByte()
    private val json = Json { ignoreUnknownKeys = true }
    private val protobuf = ProtoBuf
    private val LegacyList = kotlinx.serialization.builtins.ListSerializer(LegacyExtension.serializer())
}

private const val ANIYOMI_LABEL = "Aniyomi: "

@Serializable
private class LegacyRepo(@SerialName("index_v2") val indexV2: String? = null, val meta: Meta) {
    @Serializable
    class Meta(val name: String, val signingKeyFingerprint: String)
}

@Serializable
private class LegacyExtension(
    val name: String,
    val pkg: String,
    val apk: String,
    val lang: String = "",
    val code: Long,
    val version: String,
    val torrent: Int = 0,
    val sources: List<LegacySource>? = null,
) {
    fun indexed(folder: String): IndexedExtension {
        val anime = name.startsWith(ANIYOMI_LABEL)
        return IndexedExtension(
            name = name.removePrefix(ANIYOMI_LABEL).removePrefix(MihonKind.labelPrefix),
            packageName = pkg,
            apkUrl = "$folder/apk/$apk",
            libVersion = version.substringBeforeLast('.'),
            versionCode = code,
            versionName = version,
            languages = sources.orEmpty().map(LegacySource::lang).ifEmpty { listOf(lang) }.distinct(),
            kind = if (anime) SeriesKind.ANIME else SeriesKind.MANGA,
            torrent = torrent == 1,
        )
    }
}

@Serializable
private class LegacySource(val lang: String = "")

@OptIn(ExperimentalSerializationApi::class)
@Serializable
private class ProtoStore<E>(
    @ProtoNumber(1) val name: String = "",
    @ProtoNumber(3) val signingKey: String = "",
    @ProtoNumber(101) val extensionList: ProtoList<E>? = null,
    @ProtoNumber(102) val extensionListUrl: String? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
private class ProtoList<E>(@ProtoNumber(1) val extensions: List<E> = emptyList())

private interface ProtoEntry {
    val extensionLib: String

    fun indexed(): IndexedExtension
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
private class MangaEntry(
    @ProtoNumber(1) val name: String = "",
    @ProtoNumber(2) val packageName: String = "",
    @ProtoNumber(3) val resources: ProtoResources = ProtoResources(),
    @ProtoNumber(4) override val extensionLib: String = "",
    @ProtoNumber(5) val versionCode: Long = 0,
    @ProtoNumber(6) val versionName: String = "",
    @ProtoNumber(8) val sources: List<ProtoSource> = emptyList(),
) : ProtoEntry {
    override fun indexed() = IndexedExtension(
        name, packageName, resources.apkUrl, extensionLib, versionCode, versionName, sources.map(ProtoSource::language).distinct(), SeriesKind.MANGA,
    )
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
private class AnimeEntry(
    @ProtoNumber(1) val name: String = "",
    @ProtoNumber(2) val packageName: String = "",
    @ProtoNumber(3) val resources: ProtoResources = ProtoResources(),
    @ProtoNumber(4) override val extensionLib: String = "",
    @ProtoNumber(5) val versionCode: Long = 0,
    @ProtoNumber(6) val versionName: String = "",
    @ProtoNumber(8) val torrent: Int = 0,
    @ProtoNumber(9) val sources: List<ProtoSource> = emptyList(),
) : ProtoEntry {
    override fun indexed() = IndexedExtension(
        name, packageName, resources.apkUrl, extensionLib, versionCode, versionName, sources.map(ProtoSource::language).distinct(), SeriesKind.ANIME,
        torrent = torrent == 1,
    )
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
private class ProtoResources(@ProtoNumber(1) val apkUrl: String = "")

@OptIn(ExperimentalSerializationApi::class)
@Serializable
private class ProtoSource(@ProtoNumber(3) val language: String = "")
